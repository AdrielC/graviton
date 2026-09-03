package graviton.backend.s3

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.stores.{StoreError, StoreOperation}
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.S3Exception
import zio.test.*

object S3StoreErrorSpec extends ZIOSpecDefault:
  override def spec = suite("S3StoreError")(
    test("classifies S3 status families deterministically") {
      val denied      = classify(403)
      val conflict    = classify(412)
      val throttled   = classify(429)
      val unavailable = classify(503)
      assertTrue(
        denied.isInstanceOf[StoreError.PermissionDenied],
        !denied.retryable,
        conflict.isInstanceOf[StoreError.Conflict],
        !conflict.retryable,
        throttled.isInstanceOf[StoreError.Unavailable],
        throttled.retryable,
        unavailable.isInstanceOf[StoreError.Unavailable],
        unavailable.retryable,
      )
    },
    test("classifies keyed not-found and transport failures") {
      val key       = testKey
      val notFound  = S3StoreError.fromThrowable(StoreOperation.GetBlock, Some(key))(S3Exception.builder().statusCode(404).build())
      val transport = S3StoreError.fromThrowable(StoreOperation.PutBlock)(SdkClientException.create("connection reset"))
      assertTrue(
        notFound.isInstanceOf[StoreError.NotFound],
        !notFound.retryable,
        transport.isInstanceOf[StoreError.Unavailable],
        transport.retryable,
      )
    },
  )

  private def classify(status: Int): StoreError =
    S3StoreError.fromThrowable(StoreOperation.PutBlock)(S3Exception.builder().statusCode(status).build())

  private def testKey: BinaryKey.Block =
    val digest = Digest.fromBytes(Array.fill[Byte](32)(1)).toOption.get
    val bits   = KeyBits.create(HashAlgo.Sha256, digest, 1L).toOption.get
    BinaryKey.block(bits).toOption.get
