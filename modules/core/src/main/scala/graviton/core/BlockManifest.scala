package graviton
package core

import zio.schema.{DeriveSchema, Schema}
import zio.prelude.NonEmptyMap
import zio.Chunk
import model.*
import graviton.HashAlgorithm
import graviton.domain.*
import graviton.core.BinaryKey
import graviton.core.model.BlockLength
import graviton.core.model.Index

/**
 * Ordered manifest describing how to reassemble a file from blocks.
 */
final case class BlockManifestEntry(
  offset: Index,
  size: BlockLength,
  block: BinaryKey.CasKey.BlockKey,
)

object BlockManifestEntry:
  given Schema[BlockManifestEntry]                    = DeriveSchema.gen[BlockManifestEntry]
  given Conversion[ManifestEntry, BlockManifestEntry] = (entry: ManifestEntry) =>
    BlockManifestEntry(
      entry.offset,
      entry.size,
      entry.block.toBinaryKey,
    )
end BlockManifestEntry

final case class BlockManifest(
  fileSize: FileSize,
  fileHash: NonEmptyMap[HashAlgorithm, HashBytes],
  binaryAttributes: BinaryAttributes,
  entries: Chunk[BlockManifestEntry],
)

object BlockManifest:
  given Conversion[Manifest, BlockManifest] = (manifest: Manifest) =>
    BlockManifest(
      manifest.fileSize,
      manifest.fileHash,
      manifest.binaryAttributes,
      manifest.entries.map(a => a: BlockManifestEntry),
    )

  given Schema[BlockManifest] = DeriveSchema.gen[BlockManifest]

end BlockManifest
