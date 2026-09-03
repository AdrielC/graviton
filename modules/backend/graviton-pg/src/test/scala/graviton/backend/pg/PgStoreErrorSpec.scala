package graviton.backend.pg

import graviton.runtime.stores.{StoreError, StoreOperation}
import zio.test.*

import java.sql.SQLException

object PgStoreErrorSpec extends ZIOSpecDefault:
  override def spec = suite("PgStoreError")(
    test("classifies connection and transaction failures as retryable") {
      val connection    = PgStoreError.fromThrowable(StoreOperation.GetManifest)(new SQLException("connection lost", "08006"))
      val serialization = PgStoreError.fromThrowable(StoreOperation.PutManifest)(new SQLException("retry transaction", "40001"))
      assertTrue(
        connection.isInstanceOf[StoreError.Unavailable],
        connection.retryable,
        serialization.isInstanceOf[StoreError.Unavailable],
        serialization.retryable,
      )
    },
    test("classifies permission and uniqueness failures without retry") {
      val denied   = PgStoreError.fromThrowable(StoreOperation.GetManifest)(new SQLException("denied", "28000"))
      val conflict = PgStoreError.fromThrowable(StoreOperation.PutManifest)(new SQLException("duplicate", "23505"))
      assertTrue(
        denied.isInstanceOf[StoreError.PermissionDenied],
        !denied.retryable,
        conflict.isInstanceOf[StoreError.Conflict],
        !conflict.retryable,
      )
    },
    test("classifies invalid persisted values as corrupt data") {
      val error = PgStoreError.fromThrowable(StoreOperation.Inventory)(
        PgStoreError.CorruptStoredData("invalid stored manifest digest")
      )
      assertTrue(error.isInstanceOf[StoreError.CorruptData], !error.retryable)
    },
    test("classifies SQL value and constraint rejection as caller-visible non-retryable failures") {
      val value      = PgStoreError.fromThrowable(StoreOperation.PutManifest)(new SQLException("bad value", "22003"))
      val constraint = PgStoreError.fromThrowable(StoreOperation.PutManifest)(new SQLException("check failed", "23514"))
      assertTrue(
        value.isInstanceOf[StoreError.InvalidInput],
        !value.retryable,
        constraint.isInstanceOf[StoreError.Conflict],
        !constraint.retryable,
      )
    },
  )
