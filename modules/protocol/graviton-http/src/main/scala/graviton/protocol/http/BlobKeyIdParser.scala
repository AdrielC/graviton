package graviton.protocol.http

import graviton.core.keys.{BinaryKey, KeyBits, KeyBitsError}
import graviton.shared.ApiModels.BlobId
import io.github.iltotore.iron.zio.*
import zio.NonEmptyChunk
import zio.prelude.Validation

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.util.Try

private[http] object BlobKeyIdParser:

  enum Error derives CanEqual:
    case InvalidEncoding
    case InvalidBlobId(reason: String)
    case InvalidContentKey(error: KeyBitsError)
    case InvalidBlobKey(reason: String)

    def message: String =
      this match
        case Error.InvalidEncoding          => "Invalid blob ID encoding"
        case Error.InvalidBlobId(reason)    => s"Invalid blob ID: $reason"
        case Error.InvalidContentKey(error) => error.message
        case Error.InvalidBlobKey(reason)   => reason

  /**
   * Decode an HTTP path segment, accumulate independent identifier and key
   * diagnostics, then enforce the blob-specific size invariant.
   */
  def validate(rawId: String): Validation[Error, BinaryKey.Blob] =
    Validation
      .fromEither(decode(rawId))
      .flatMap { decoded =>
        Validation
          .validateWith(
            BlobId.validation(decoded).mapError(Error.InvalidBlobId.apply),
            KeyBits.fromString(decoded).mapError(Error.InvalidContentKey.apply),
          )((_, bits) => bits)
          .flatMap(bits => Validation.fromEither(BinaryKey.blob(bits).left.map(Error.InvalidBlobKey.apply)))
      }

  /** Explicit rendered-error adapter for the existing HTTP handler shape. */
  def parse(rawId: String): Either[String, BinaryKey.Blob] =
    validate(rawId).toEitherWith(render)

  private def decode(rawId: String): Either[Error, String] =
    Try(URLDecoder.decode(rawId, StandardCharsets.UTF_8)).toEither.left.map(_ => Error.InvalidEncoding)

  private def render(errors: NonEmptyChunk[Error]): String =
    errors.map(_.message).mkString("; ")
