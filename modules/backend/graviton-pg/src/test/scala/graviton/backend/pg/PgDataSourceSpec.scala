package graviton.backend.pg

import zio.test.*

object PgDataSourceSpec extends ZIOSpecDefault:

  override def spec = suite("PostgreSQL connection pool configuration")(
    test("accepts the production defaults") {
      assertTrue(PgDataSource.PoolConfig.Default.validate == Right(PgDataSource.PoolConfig.Default))
    },
    test("rejects a pool whose idle floor exceeds its connection ceiling") {
      val invalid = PgDataSource.PoolConfig.Default.copy(maximumPoolSize = 4, minimumIdle = 5)
      assertTrue(invalid.validate.left.exists(_.contains("PG_POOL_MIN_IDLE")))
    },
    test("rejects timeouts that Hikari would silently normalize") {
      val shortConnection  = PgDataSource.PoolConfig.Default.copy(connectionTimeoutMillis = 249L)
      val longValidation   = PgDataSource.PoolConfig.Default.copy(validationTimeoutMillis = 10000L)
      val shortIdle        = PgDataSource.PoolConfig.Default.copy(idleTimeoutMillis = 9999L)
      val shortLifetime    = PgDataSource.PoolConfig.Default.copy(maxLifetimeMillis = 29999L)
      val invalidKeepalive = PgDataSource.PoolConfig.Default.copy(keepaliveTimeMillis = 1800000L)

      assertTrue(
        shortConnection.validate.isLeft,
        longValidation.validate.isLeft,
        shortIdle.validate.isLeft,
        shortLifetime.validate.isLeft,
        invalidKeepalive.validate.isLeft,
      )
    },
  )
