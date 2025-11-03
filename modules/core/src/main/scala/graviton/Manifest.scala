package graviton

import zio.schema.{DeriveSchema, Schema}
import graviton.core.model.Index
import graviton.core.BinaryAttributes
import graviton.core.model.FileSize
import graviton.core.model.BlockSize
import graviton.domain.HashBytes
import zio.prelude.NonEmptyMap
import zio.Chunk
import graviton.HashAlgorithm
import graviton.core.BlockManifestEntry

/**
 * Ordered manifest describing how to reassemble a Blob from Blocks.
 */
final case class ManifestEntry(
  offset: Index,
  size: BlockSize,
  block: BlockKey,
)

object ManifestEntry:
  given Schema[ManifestEntry] = DeriveSchema.gen[ManifestEntry]
  given Conversion[BlockManifestEntry, ManifestEntry] = (entry: BlockManifestEntry) => ManifestEntry(
    entry.offset,
    entry.size,
    entry.block,
  )

final case class Manifest(
  fileSize: FileSize,
  fileHash: NonEmptyMap[HashAlgorithm, HashBytes],
  binaryAttributes: BinaryAttributes,
  entries: Chunk[ManifestEntry]
)

object Manifest:
  given Schema[Manifest] = DeriveSchema.gen[Manifest]
