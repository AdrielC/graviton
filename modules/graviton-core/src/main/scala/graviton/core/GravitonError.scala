package graviton.core

/**
 * Unified error hierarchy for the Graviton storage engine.
 *
 * All domain errors flow through this sealed trait. Each layer is free to
 * handle the full hierarchy or to pattern-match on specific subtypes.
 *
 * Design principles:
 *   - Every variant carries a human-readable `message`.
 *   - Optional `cause` preserves the original exception chain for logging.
 *   - The hierarchy is flat-ish (one level of sealed case classes) so callers
 *     can exhaust the match without deep nesting.
 *   - Pure core code keeps using `Either[String, A]`; this hierarchy is for
 *     effectful boundaries where typed errors improve diagnostics and routing.
 */
sealed trait GravitonError extends Product with Serializable:
  def message: String
  def cause: Option[Throwable] = None

  /** Bridge to `Throwable` for layers that use untyped error channels. */
  def toThrowable: Throwable = GravitonException(this)

object GravitonError:

  // --- Validation -----------------------------------------------------------

  /** A value failed domain validation (refined-type boundary, manifest check, etc.). */
  final case class ValidationError(
    message: String,
    field: Option[String] = None,
    override val cause: Option[Throwable] = None,
  ) extends GravitonError

  // --- Codec / serialization ------------------------------------------------

  /** Binary or JSON encode/decode failure (scodec, zio-schema, protobuf). */
  final case class CodecError(
    message: String,
    context: Option[String] = None,
    override val cause: Option[Throwable] = None,
  ) extends GravitonError

  // --- Configuration --------------------------------------------------------

  /** Config parsing or missing-key error at startup or reload time. */
  final case class ConfigError(
    message: String,
    override val cause: Option[Throwable] = None,
  ) extends GravitonError

  // --- Storage / IO ---------------------------------------------------------

  /** Block/blob store read, write, or delete failure. */
  final case class StoreError(
    message: String,
    override val cause: Option[Throwable] = None,
  ) extends GravitonError

  // --- Chunker --------------------------------------------------------------

  /** Chunking pipeline failure (invalid bounds, delimiter, block construction). */
  final case class ChunkerError(
    message: String,
    override val cause: Option[Throwable] = None,
  ) extends GravitonError

  // --- Generic / catch-all --------------------------------------------------

  /** An error that doesn't fit the above categories (escape hatch). */
  final case class InternalError(
    message: String,
    override val cause: Option[Throwable] = None,
  ) extends GravitonError

  // --- Conversions ----------------------------------------------------------

  /** Lift a plain `String` error (typical in `Either[String, A]` flows) into a `ValidationError`. */
  def fromString(msg: String): GravitonError =
    ValidationError(msg)

  /** Lift a `Throwable` into the most appropriate variant. */
  def fromThrowable(t: Throwable): GravitonError =
    t match
      case ge: GravitonException => ge.error
      case other                 => InternalError(Option(other.getMessage).getOrElse(other.getClass.getSimpleName), Some(other))

end GravitonError

/**
 * Throwable wrapper for `GravitonError`, used when bridging into untyped error channels.
 *
 * When constructed from a typed `GravitonError`, the original variant is preserved
 * and can be recovered via [[error]]. Plain-string construction yields `InternalError`.
 */
final class GravitonException private (
  msg: String,
  causedBy: Throwable | Null,
  private val typedError: Option[GravitonError],
) extends Exception(msg, causedBy):

  /** Recover the typed error. Returns the original variant if constructed from one. */
  def error: GravitonError = typedError.getOrElse(GravitonError.InternalError(msg, Option(causedBy)))

object GravitonException:
  def apply(msg: String): GravitonException                   = new GravitonException(msg, null, None)
  def apply(msg: String, cause: Throwable): GravitonException = new GravitonException(msg, cause, None)
  def apply(error: GravitonError): GravitonException          =
    new GravitonException(error.message, error.cause.orNull, Some(error))
