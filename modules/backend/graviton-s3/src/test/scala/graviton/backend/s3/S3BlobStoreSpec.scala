package graviton.backend.s3

import graviton.core.attributes.BinaryAttributes
import graviton.core.types.*
import graviton.runtime.model.BlobWritePlan
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import zio.*
import zio.stream.*
import zio.test.*

import java.lang.reflect.Proxy
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

object S3BlobStoreSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("S3BlobStore")(
      test("rejects invalid BlobWritePlan attributes before calling S3 client") {
        val calls = new AtomicInteger(0)
        val store = new S3BlobStore(recordingClient(calls), testConfig)
        val data  = Chunk.fromArray("blob-data".getBytes(StandardCharsets.UTF_8))
        val attrs =
          BinaryAttributes.empty
            .advertiseDigest(Algo.applyUnsafe("sha-256"), HexLower.applyUnsafe("a" * 40))
        for exit <- ZStream
                      .fromChunk(data)
                      .run(store.put(BlobWritePlan(attributes = attrs)))
                      .exit
        yield assertTrue(
          exit match
            case Exit.Failure(cause) =>
              cause.failureOption.exists(_.getMessage.contains("Invalid binary attributes in BlobWritePlan"))
            case Exit.Success(_)     => false
          ,
          calls.get() == 0,
        )
      },
      test("exact multipart boundary does not upload an empty final part") {
        val calls = MultipartCalls()
        val store = new S3BlobStore(multipartClient(calls), testConfig)
        val data  = Chunk.fromArray(Array.fill[Byte](S3BlobStore.PartSize.Default.value)(1))

        for result <- ZStream.fromChunk(data).run(store.put())
        yield assertTrue(
          result.key.bits.size == data.length.toLong,
          calls.created.get() == 1,
          calls.uploaded.get() == 1,
          calls.completed.get() == 1,
          calls.copied.get() == 1,
          calls.deleted.get() == 1,
        )
      },
      test("one oversized upstream chunk is split into bounded S3 parts") {
        val calls = MultipartCalls()
        val store = new S3BlobStore(multipartClient(calls), testConfig)
        val data  = Chunk.fromArray(Array.fill[Byte](S3BlobStore.PartSize.Default.value + 17)(2))

        for result <- ZStream.fromChunk(data).run(store.put())
        yield assertTrue(
          result.key.bits.size == data.length.toLong,
          calls.uploaded.get() == 2,
          calls.completed.get() == 1,
        )
      },
      test("an interrupted multipart upload is explicitly aborted") {
        val calls = MultipartCalls()
        val store = new S3BlobStore(multipartClient(calls), testConfig)
        val data  = Chunk.fromArray(Array.fill[Byte](S3BlobStore.PartSize.Default.value)(3))

        for exit <- (ZStream.fromChunk(data) ++ ZStream.fail(new RuntimeException("intentional failure"))).run(store.put()).exit
        yield assertTrue(exit.isFailure, calls.created.get() == 1, calls.aborted.get() == 1, calls.completed.get() == 0)
      },
      test("adaptive bounded parts provide at least one TiB of multipart capacity") {
        assertTrue(
          S3BlobStore.partSizeForNumber(S3BlobStore.PartSize.Default, 1) == 5 * 1024 * 1024,
          S3BlobStore.partSizeForNumber(S3BlobStore.PartSize.Default, 257) == 10 * 1024 * 1024,
          S3BlobStore.partSizeForNumber(S3BlobStore.PartSize.Default, 1281) == S3BlobStore.MaxBufferedPartBytes,
          S3BlobStore.multipartCapacityBytes(S3BlobStore.PartSize.Default) >= S3BlobStore.OneTebibyte,
        )
      },
      test("one TiB promotion uses bounded server-side multipart copy") {
        val calls = MultipartCalls()

        for
          ranges <- ZIO.fromEither(S3BlobStore.copyRanges(S3BlobStore.OneTebibyte))
          _      <- S3BlobStore.promoteTempObject(
                      multipartClient(calls),
                      sourceBucket = "graviton-test-tmp",
                      sourceKey = "temporary/object",
                      destinationBucket = "graviton-test",
                      destinationKey = "sha256/content-key",
                      size = S3BlobStore.OneTebibyte,
                    )
        yield assertTrue(
          ranges.length == 2048,
          ranges.head.start == 0L,
          ranges.last.endInclusive == S3BlobStore.OneTebibyte - 1L,
          ranges.zip(ranges.drop(1)).forall { case (left, right) => left.endInclusive + 1L == right.start },
          calls.created.get() == 1,
          calls.copied.get() == 0,
          calls.copiedParts.get() == ranges.length,
          calls.completed.get() == 1,
          calls.aborted.get() == 0,
        )
      },
    )

  private final case class MultipartCalls(
    created: AtomicInteger = new AtomicInteger(0),
    uploaded: AtomicInteger = new AtomicInteger(0),
    completed: AtomicInteger = new AtomicInteger(0),
    aborted: AtomicInteger = new AtomicInteger(0),
    copied: AtomicInteger = new AtomicInteger(0),
    copiedParts: AtomicInteger = new AtomicInteger(0),
    deleted: AtomicInteger = new AtomicInteger(0),
  )

  private val testConfig: S3BlobStoreConfig =
    val base =
      S3Config(
        bucket = "graviton-test",
        region = Region.US_EAST_1,
        endpointOverride = Some(URI.create("http://localhost:9000")),
        accessKeyId = Some("test"),
        secretAccessKey = Some("test"),
        forcePathStyle = true,
        prefix = "cas/blobs",
      )
    S3BlobStoreConfig(
      blobs = base,
      tmp = base.copy(bucket = "graviton-test-tmp", prefix = "cas/tmp"),
    )

  private def recordingClient(calls: AtomicInteger): S3Client =
    Proxy
      .newProxyInstance(
        classOf[S3Client].getClassLoader,
        Array(classOf[S3Client]),
        (_, method, _) =>
          method.getName match
            case "close"    => null
            case "toString" =>
              "recording-s3-client"
            case _          =>
              calls.incrementAndGet()
              throw new UnsupportedOperationException(s"S3 client method invoked unexpectedly: ${method.getName}"),
      )
      .asInstanceOf[S3Client]

  private def multipartClient(calls: MultipartCalls): S3Client =
    Proxy
      .newProxyInstance(
        classOf[S3Client].getClassLoader,
        Array(classOf[S3Client]),
        (_, method, _) =>
          method.getName match
            case "createMultipartUpload"   =>
              calls.created.incrementAndGet()
              CreateMultipartUploadResponse.builder().uploadId("upload-id").build()
            case "uploadPart"              =>
              val part = calls.uploaded.incrementAndGet()
              UploadPartResponse.builder().eTag(s"etag-$part").build()
            case "completeMultipartUpload" =>
              calls.completed.incrementAndGet()
              CompleteMultipartUploadResponse.builder().build()
            case "abortMultipartUpload"    =>
              calls.aborted.incrementAndGet()
              AbortMultipartUploadResponse.builder().build()
            case "copyObject"              =>
              calls.copied.incrementAndGet()
              CopyObjectResponse.builder().build()
            case "uploadPartCopy"          =>
              val part = calls.copiedParts.incrementAndGet()
              UploadPartCopyResponse.builder().copyPartResult(CopyPartResult.builder().eTag(s"copy-etag-$part").build()).build()
            case "deleteObject"            =>
              calls.deleted.incrementAndGet()
              DeleteObjectResponse.builder().build()
            case "close"                   => null
            case "serviceName"             => "s3"
            case "toString"                => "multipart-s3-client"
            case other                     =>
              throw new UnsupportedOperationException(s"Unexpected S3 client method: $other"),
      )
      .asInstanceOf[S3Client]
