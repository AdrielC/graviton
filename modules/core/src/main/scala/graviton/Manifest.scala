package graviton

import zio.schema.{DeriveSchema, Schema}
import graviton.core.model.Index
import graviton.core.BinaryAttributes
import graviton.core.model.FileSize
import graviton.core.model.BlockSize
import graviton.domain.HashBytes
import zio.NonEmptyChunk
import graviton.HashAlgorithm
import graviton.core.BlockManifestEntry
import graviton.core.BlockManifest
import Hash.given

private final case class AlgoHash(algo: HashAlgorithm, hash: HashBytes)
private given Schema[AlgoHash]                              = DeriveSchema.gen[AlgoHash]


/**
 * Ordered manifest describing how to reassemble a Blob from Blocks.
 */
final case class ManifestEntry(
  offset: Index,
  size: BlockSize,
  block: NonEmptyChunk[BlockKey],
)

object ManifestEntry:
  given Schema[ManifestEntry]                         = DeriveSchema.gen[ManifestEntry]
  given Conversion[BlockManifestEntry, ManifestEntry] = (entry: BlockManifestEntry) =>
    ManifestEntry(
      entry.offset,
      entry.size,
      entry.block.map(b => b: BlockKey),
    )

final case class Manifest(
  fileSize: FileSize,
  fileHash: Hash.MultiHash,
  binaryAttributes: BinaryAttributes,
  entries: NonEmptyChunk[ManifestEntry],
)

object Manifest:
  given Schema[Manifest] = DeriveSchema.gen[Manifest]

  given Conversion[BlockManifest, Manifest] = (manifest: BlockManifest) =>
    Manifest(
      manifest.fileSize,
      manifest.fileHash,
      manifest.binaryAttributes,
      manifest.entries.map(a => a: ManifestEntry),
    )
