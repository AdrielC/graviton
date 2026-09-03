package graviton.runtime.stores

import zio.test.*

object BackendInitErrorSpec extends ZIOSpecDefault:
  override def spec = suite("BackendInitError")(
    test("keeps configuration failures distinct from acquisition failures") {
      val invalid = BackendInitError.fromThrowable(StoreBackend.S3)(new IllegalArgumentException("bad endpoint"))
      val failed  = BackendInitError.fromThrowable(StoreBackend.PostgreSql)(new RuntimeException("offline"))
      assertTrue(
        invalid.isInstanceOf[BackendInitError.InvalidConfiguration],
        invalid.backend == StoreBackend.S3,
        failed.isInstanceOf[BackendInitError.AcquisitionFailed],
        failed.backend == StoreBackend.PostgreSql,
        failed.getCause != null,
      )
    }
  )
