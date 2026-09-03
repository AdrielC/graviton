package graviton.runtime.stores

/** Typed failures raised while validating or acquiring a storage backend. */
sealed abstract class BackendInitError(
  val backend: StoreBackend,
  message: String,
  cause: Throwable | Null = null,
) extends Exception(message, cause)

object BackendInitError:

  final case class InvalidConfiguration(
    override val backend: StoreBackend,
    reason: String,
  ) extends BackendInitError(backend, reason)

  final case class AcquisitionFailed(
    override val backend: StoreBackend,
    underlying: Throwable,
  ) extends BackendInitError(
        backend,
        s"${backend.value} backend initialization failed",
        underlying,
      )

  def fromThrowable(backend: StoreBackend)(error: Throwable): BackendInitError =
    error match
      case typed: BackendInitError           => typed
      case invalid: IllegalArgumentException =>
        InvalidConfiguration(backend, Option(invalid.getMessage).getOrElse("invalid backend configuration"))
      case other                             => AcquisitionFailed(backend, other)
