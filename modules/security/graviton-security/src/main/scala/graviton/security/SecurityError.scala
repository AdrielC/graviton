package graviton.security

/**
 * Typed security errors surfaced by auth, authorization, rate-limiting, and
 * audit layers. These are separate from [[graviton.core.GravitonError]] so
 * middleware can pattern-match without importing the core hierarchy, and so
 * the redactor can safely format them for clients.
 */
sealed trait SecurityError extends Product with Serializable:
  def message: String
  def cause: Option[Throwable] = None

object SecurityError:

  /** No credentials, malformed token, or JWT verification failure. */
  final case class Unauthenticated(
    message: String,
    override val cause: Option[Throwable] = None,
  ) extends SecurityError

  /** Valid credentials, but the caller lacks a required capability. */
  final case class Forbidden(
    message: String,
    required: Option[Capability] = None,
  ) extends SecurityError

  /** Rate limit exceeded for the caller/resource combination. */
  final case class RateLimited(message: String) extends SecurityError

  /** Request body exceeded the configured max size. */
  final case class PayloadTooLarge(message: String) extends SecurityError

  /** Internal audit pipeline failure (should page; never leaked to client). */
  final case class AuditFailure(
    message: String,
    override val cause: Option[Throwable] = None,
  ) extends SecurityError

  /** Configuration is missing or invalid at startup / verification time. */
  final case class MisconfiguredSecurity(
    message: String,
    override val cause: Option[Throwable] = None,
  ) extends SecurityError
