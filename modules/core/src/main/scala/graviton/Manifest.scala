package graviton

import zio.schema.{DeriveSchema, Schema}

/**
 * Ordered manifest describing how to reassemble a Blob from Blocks.
 */
final case class ManifestEntry(
  offset: Long,
  size: Int,
  block: BlockKey,
)

object ManifestEntry:
  given Schema[ManifestEntry] = DeriveSchema.gen[ManifestEntry]

final case class Manifest(
  entries: Vector[ManifestEntry]
)

object Manifest:
  given Schema[Manifest] = DeriveSchema.gen[Manifest]

