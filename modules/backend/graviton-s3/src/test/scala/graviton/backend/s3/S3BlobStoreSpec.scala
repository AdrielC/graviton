package graviton.backend.s3

import graviton.core.attributes.BinaryAttributes
import graviton.core.types.*
import graviton.runtime.model.BlobWritePlan
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
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
      }
    )

  private val testConfig: S3BlobStoreConfig =
    val base =
      S3Config(
        bucket = "graviton-test",
        region = Region.US_EAST_1,
        endpointOverride = Some(URI.create("http://localhost:9000")),
        accessKeyId = "test",
        secretAccessKey = "test",
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
