package graviton.runtime.upload

import zio.stream.ZStream

/** Typed failure channel for an arbitrary-size upload source. */
sealed abstract class UploadSourceError(message: String, cause: Throwable | Null = null) extends Exception(message, cause)

object UploadSourceError:
  final case class Transport(underlying: Throwable)                              extends UploadSourceError("upload transport failed", underlying)
  final case class Rejected(reason: String, underlying: Throwable | Null = null) extends UploadSourceError(reason, underlying)

/** A payload stream whose public error channel contains only upload failures. */
final case class UploadSource private (bytes: ZStream[Any, UploadSourceError, Byte])

object UploadSource:
  def typed(bytes: ZStream[Any, UploadSourceError, Byte]): UploadSource = UploadSource(bytes)

  /** Compatibility adapter for transports released with `Throwable` streams. */
  def fromThrowable(bytes: ZStream[Any, Throwable, Byte]): UploadSource =
    UploadSource(
      bytes.mapError {
        case typed: UploadSourceError => typed
        case other                    => UploadSourceError.Transport(other)
      }
    )
