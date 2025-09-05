package graviton

import zio.schema.{DeriveSchema, Schema}

/**
 * Blob identity: full content hash + algorithm + total size and optional media type hint.
 * Independent of chunking/manifest details.
 */
final case class BlobKey(
  hash: Hash,
  algo: HashAlgorithm,
  size: Long,
  mediaTypeHint: Option[String],
)

object BlobKey:
  given Schema[BlobKey] = DeriveSchema.gen[BlobKey]

