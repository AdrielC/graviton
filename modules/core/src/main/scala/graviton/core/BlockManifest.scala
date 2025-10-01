package graviton.core

import zio.schema.{DeriveSchema, Schema}

/**
 * Ordered manifest describing how to reassemble a file from blocks.
 */
final case class BlockManifestEntry(
  offset: Long,
  size: Int,
  block: FileKey.CasKey,
)

object BlockManifestEntry:
  given Schema[BlockManifestEntry] = DeriveSchema.gen[BlockManifestEntry]

final case class BlockManifest(
  entries: Vector[BlockManifestEntry]
)

object BlockManifest:
  given Schema[BlockManifest] = DeriveSchema.gen[BlockManifest]
