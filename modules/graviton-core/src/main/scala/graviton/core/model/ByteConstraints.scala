package graviton.core.model

import graviton.core.types.{FileSize, MaxBlockBytes as CoreMaxBlockBytes}

/**
 * Centralised size/index limits for block and upload handling. Constants align with the
 * legacy values on `main`, ensuring behaviour stays stable while richer wrappers evolve.
 */
object ByteConstraints:

  val MinBlockBytes: Int       = 1
  val MaxBlockBytes: Int       = CoreMaxBlockBytes
  val MinUploadChunkBytes: Int = 1
  val MaxUploadChunkBytes: Int = MaxBlockBytes
  // File/blob sizes must be > 0. Empty blobs are not supported.
  val MinFileBytes: Long       = 1L

  /**
   * Enforce a backend defined limit on a file/blob size. Limits vary per store (filesystem, S3,
   * database LOBs, etc) so we keep the refinement dynamic and let configs call this helper.
   */
  def enforceFileLimit(value: Long, maxBytes: Long): Either[String, FileSize] =
    if value > maxBytes then Left(s"File size exceeds backend limit $maxBytes bytes (got $value)")
    else FileSize.either(value)

end ByteConstraints
