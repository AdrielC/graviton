package graviton.backend.s3

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.HashAlgo
import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlockStoredStatus, CanonicalBlock}
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import zio.*
import zio.test.*

import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.jdk.CollectionConverters.*

object S3BlockStoreSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("S3BlockStore")(
      test("fresh blocks use one conditional PUT with a service-verified checksum") {
        val calls   = BlockCalls()
        val present = new AtomicBoolean(false)
        val store   = new S3BlockStore(conditionalClient(calls, present), testConfig)

        for
          block   <- canonical("one-request-fresh-block")
          stored  <- store.putBlock(block)
          request  = calls.request.get()
          expected = Base64.getEncoder.encodeToString(block.key.bits.digest.toInteropArray)
        yield assertTrue(
          stored.status == BlockStoredStatus.Fresh,
          calls.put.get() == 1,
          calls.head.get() == 0,
          calls.get.get() == 0,
          request.ifNoneMatch() == "*",
          request.checksumSHA256() == expected,
          request.metadata().get("graviton-cas-version") == "1",
          request.metadata().get("graviton-content-key") == block.key.bits.render,
          request.metadata().get("graviton-sha256") == expected,
        )
      },
      test("duplicates use conditional PUT plus HEAD without downloading the block") {
        val calls   = BlockCalls()
        val present = new AtomicBoolean(false)
        val store   = new S3BlockStore(conditionalClient(calls, present), testConfig)

        for
          block     <- canonical("metadata-proven-duplicate")
          first     <- store.putBlock(block)
          duplicate <- store.putBlock(block)
        yield assertTrue(
          first.status == BlockStoredStatus.Fresh,
          duplicate.status == BlockStoredStatus.Duplicate,
          calls.put.get() == 2,
          calls.head.get() == 1,
          calls.get.get() == 0,
        )
      },
      test("objects without the current CAS proof fail closed without downloading bytes") {
        val calls = BlockCalls()

        for
          block <- canonical("missing-proof")
          store  = new S3BlockStore(missingProofClient(calls, block), testConfig)
          exit  <- store.putBlock(block).exit
        yield assertTrue(
          exit.isFailure,
          calls.put.get() == 1,
          calls.head.get() == 1,
          calls.get.get() == 0,
        )
      },
      test("inconsistent CAS proof fails closed without downloading bytes") {
        val calls = BlockCalls()

        for
          block <- canonical("inconsistent-proof")
          store  = new S3BlockStore(inconsistentProofClient(calls, block), testConfig)
          exit  <- store.putBlock(block).exit
        yield assertTrue(
          exit.isFailure,
          calls.put.get() == 1,
          calls.head.get() == 1,
          calls.get.get() == 0,
        )
      },
      test("a conditional-write conflict retries the idempotent PUT") {
        val calls = BlockCalls()

        for
          block  <- canonical("retry-conflict")
          store   = new S3BlockStore(conflictThenFreshClient(calls), testConfig)
          fiber  <- store.putBlock(block).fork
          _      <- ZIO.yieldNow
          _      <- TestClock.adjust(1.second)
          stored <- fiber.join
        yield assertTrue(stored.status == BlockStoredStatus.Fresh, calls.put.get() == 2)
      },
      test("rejects a payload whose declared content-key size is inconsistent") {
        val calls = BlockCalls()

        for
          keyBlock <- canonical("short")
          payload   = Chunk.fromArray("longer".getBytes(StandardCharsets.UTF_8))
          invalid  <-
            ZIO.fromEither(CanonicalBlock.make(keyBlock.key, payload, BinaryAttributes.empty)).mapError(new IllegalArgumentException(_))
          store     = new S3BlockStore(unexpectedClient(calls), testConfig)
          exit     <- store.putBlock(invalid).exit
        yield assertTrue(exit.isFailure, calls.put.get() == 0, calls.head.get() == 0, calls.get.get() == 0)
      },
    )

  private final case class BlockCalls(
    put: AtomicInteger = new AtomicInteger(0),
    head: AtomicInteger = new AtomicInteger(0),
    get: AtomicInteger = new AtomicInteger(0),
    request: AtomicReference[PutObjectRequest] = new AtomicReference[PutObjectRequest](),
  )

  private val testConfig: S3BlockStoreConfig =
    S3BlockStoreConfig(
      S3Config(
        bucket = "graviton-test",
        region = Region.US_EAST_1,
        prefix = "cas/blocks",
      )
    )

  private def canonical(value: String): Task[CanonicalBlock] =
    val bytes = Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))
    for
      bits  <- ZIO.fromEither(HashAlgo.Sha256(bytes)).mapError(new IllegalArgumentException(_))
      key   <- ZIO.fromEither(BinaryKey.block(bits)).mapError(new IllegalArgumentException(_))
      block <- ZIO.fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty)).mapError(new IllegalArgumentException(_))
    yield block

  private def conditionalClient(calls: BlockCalls, present: AtomicBoolean): S3Client =
    val metadata = new AtomicReference[java.util.Map[String, String]]()
    val length   = new AtomicReference[java.lang.Long]()
    Proxy
      .newProxyInstance(
        classOf[S3Client].getClassLoader,
        Array(classOf[S3Client]),
        (_, method, args) =>
          method.getName match
            case "putObject"   =>
              calls.put.incrementAndGet()
              val request = args(0).asInstanceOf[PutObjectRequest]
              if present.compareAndSet(false, true) then
                calls.request.set(request)
                metadata.set(request.metadata())
                length.set(request.contentLength())
                PutObjectResponse.builder().checksumSHA256(request.checksumSHA256()).build()
              else throw s3Failure(412, "PreconditionFailed")
            case "headObject"  =>
              calls.head.incrementAndGet()
              HeadObjectResponse.builder().contentLength(length.get()).metadata(metadata.get()).build()
            case "getObject"   =>
              calls.get.incrementAndGet()
              throw new AssertionError("metadata-proven duplicate must not download the object")
            case "close"       => null
            case "serviceName" => "s3"
            case "toString"    => "conditional-block-client"
            case other         => throw new UnsupportedOperationException(s"Unexpected S3 client method: $other"),
      )
      .asInstanceOf[S3Client]

  private def missingProofClient(calls: BlockCalls, block: CanonicalBlock): S3Client =
    Proxy
      .newProxyInstance(
        classOf[S3Client].getClassLoader,
        Array(classOf[S3Client]),
        (_, method, _) =>
          method.getName match
            case "putObject"   =>
              calls.put.incrementAndGet()
              throw s3Failure(412, "PreconditionFailed")
            case "headObject"  =>
              calls.head.incrementAndGet()
              HeadObjectResponse
                .builder()
                .contentLength(block.size.value.toLong)
                .metadata(Map.empty[String, String].asJava)
                .build()
            case "getObject"   =>
              calls.get.incrementAndGet()
              throw new AssertionError("an object without current proof metadata must not be downloaded")
            case "close"       => null
            case "serviceName" => "s3"
            case "toString"    => "missing-proof-block-client"
            case other         => throw new UnsupportedOperationException(s"Unexpected S3 client method: $other"),
      )
      .asInstanceOf[S3Client]

  private def inconsistentProofClient(calls: BlockCalls, block: CanonicalBlock): S3Client =
    val metadata = Map(
      "graviton-cas-version" -> "1",
      "graviton-content-key" -> block.key.bits.render,
      "graviton-sha256"      -> "not-the-real-checksum",
    ).asJava

    Proxy
      .newProxyInstance(
        classOf[S3Client].getClassLoader,
        Array(classOf[S3Client]),
        (_, method, _) =>
          method.getName match
            case "putObject"   =>
              calls.put.incrementAndGet()
              throw s3Failure(412, "PreconditionFailed")
            case "headObject"  =>
              calls.head.incrementAndGet()
              HeadObjectResponse.builder().contentLength(block.size.value.toLong).metadata(metadata).build()
            case "getObject"   =>
              calls.get.incrementAndGet()
              throw new AssertionError("inconsistent proof must fail before download")
            case "close"       => null
            case "serviceName" => "s3"
            case "toString"    => "inconsistent-proof-client"
            case other         => throw new UnsupportedOperationException(s"Unexpected S3 client method: $other"),
      )
      .asInstanceOf[S3Client]

  private def conflictThenFreshClient(calls: BlockCalls): S3Client =
    Proxy
      .newProxyInstance(
        classOf[S3Client].getClassLoader,
        Array(classOf[S3Client]),
        (_, method, _) =>
          method.getName match
            case "putObject"   =>
              if calls.put.incrementAndGet() == 1 then throw s3Failure(409, "ConditionalRequestConflict")
              else PutObjectResponse.builder().build()
            case "close"       => null
            case "serviceName" => "s3"
            case "toString"    => "conflict-block-client"
            case other         => throw new UnsupportedOperationException(s"Unexpected S3 client method: $other"),
      )
      .asInstanceOf[S3Client]

  private def unexpectedClient(calls: BlockCalls): S3Client =
    Proxy
      .newProxyInstance(
        classOf[S3Client].getClassLoader,
        Array(classOf[S3Client]),
        (_, method, _) =>
          method.getName match
            case "putObject"   => calls.put.incrementAndGet(); throw new AssertionError("unexpected PUT")
            case "headObject"  => calls.head.incrementAndGet(); throw new AssertionError("unexpected HEAD")
            case "getObject"   => calls.get.incrementAndGet(); throw new AssertionError("unexpected GET")
            case "close"       => null
            case "serviceName" => "s3"
            case "toString"    => "unexpected-block-client"
            case other         => throw new UnsupportedOperationException(s"Unexpected S3 client method: $other"),
      )
      .asInstanceOf[S3Client]

  private def s3Failure(status: Int, code: String): S3Exception =
    val builder = S3Exception.builder()
    val _       = builder.statusCode(status)
    val _       = builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
    val _       = builder.message(code)
    builder.build().asInstanceOf[S3Exception]
