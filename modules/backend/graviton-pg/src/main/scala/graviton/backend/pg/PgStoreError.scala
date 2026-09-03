package graviton.backend.pg

import graviton.runtime.stores.{StoreBackend, StoreError, StoreOperation}

import java.sql.SQLException

/** PostgreSQL-specific failure classification kept at the adapter boundary. */
private[pg] object PgStoreError:

  final case class CorruptStoredData(reason: String, underlying: Throwable | Null = null) extends RuntimeException(reason, underlying)

  def corrupt(operation: StoreOperation, reason: String, underlying: Throwable | Null = null): StoreError =
    StoreError.CorruptData(operation, reason, underlying)

  def corruptValue[A](field: String, value: Either[String, A]): A =
    value.fold(reason => throw CorruptStoredData(s"invalid stored $field: $reason"), identity)

  def corruptOption[A](field: String, value: Option[A]): A =
    value.getOrElse(throw CorruptStoredData(s"invalid stored $field"))

  def fromThrowable(
    operation: StoreOperation,
    retryUnknown: Boolean = false,
  )(error: Throwable): StoreError =
    error match
      case typed: StoreError          => typed
      case corrupt: CorruptStoredData =>
        StoreError.CorruptData(operation, corrupt.reason, corrupt)
      case sql: SQLException          => classifySql(operation, sql, retryUnknown)
      case other                      =>
        StoreError.fromThrowable(operation, StoreBackend.PostgreSql, retryUnknown)(other)

  private def classifySql(operation: StoreOperation, error: SQLException, retryUnknown: Boolean): StoreError =
    val state  = Option(error.getSQLState).getOrElse("")
    val reason = Option(error.getMessage).filter(_.nonEmpty).getOrElse("PostgreSQL operation failed")

    if state.startsWith("08") || Set("40001", "40P01", "55P03", "57P01", "57P02", "57P03").contains(state) then
      StoreError.Unavailable(operation, StoreBackend.PostgreSql, error)
    else if state == "23505" then StoreError.Conflict(operation, "PostgreSQL uniqueness constraint rejected the operation")
    else if state.startsWith("22") then StoreError.InvalidInput(operation, s"PostgreSQL rejected the value (SQLSTATE $state): $reason")
    else if state.startsWith("23") then
      StoreError.Conflict(operation, s"PostgreSQL constraint rejected the operation (SQLSTATE $state): $reason")
    else if state.startsWith("28") then StoreError.PermissionDenied(operation, StoreBackend.PostgreSql, error)
    else StoreError.BackendFailure(operation, StoreBackend.PostgreSql, error, retryUnknown)
