package graviton.core.locator

import graviton.core.types.{LocatorBucket, LocatorPath, LocatorScheme}
import graviton.core.types.given
import zio.schema.{DeriveSchema, Schema}

/**
 * A backend-neutral location pointer for a stored blob.
 *
 * This is a *locator*, not a semantic identifier:
 * - it is safe to render in logs and UIs
 * - it may change if storage layout changes
 * - it MUST NOT be used for hashed identity derivation
 *
 * The `scheme://bucket/path` form is intended to be human-friendly and portable across backends.
 */
final case class BlobLocator(
  scheme: LocatorScheme,
  bucket: LocatorBucket,
  path: LocatorPath,
):
  /** Render as `scheme://bucket/path`. */
  def render: String =
    s"${scheme.value}://${bucket.value}/${path.value}"

object BlobLocator:
  given Schema[BlobLocator] = DeriveSchema.gen[BlobLocator]

  /**
   * Construct a locator from raw parts, validating with the core constraints.
   *
   * Keep this at module boundaries (config parsing, HTTP/gRPC, CLI).
   */
  def from(scheme: String, bucket: String, path: String): Either[String, BlobLocator] =
    for
      s <- LocatorScheme.either(Option(scheme).getOrElse("").trim.toLowerCase)
      b <- LocatorBucket.either(Option(bucket).getOrElse("").trim)
      p <- LocatorPath.either(Option(path).getOrElse("").trim)
    yield BlobLocator(s, b, p)
