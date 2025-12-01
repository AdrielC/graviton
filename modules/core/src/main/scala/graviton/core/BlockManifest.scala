package graviton
package core

import zio.schema.{DeriveSchema, Schema}
import zio.prelude.NonEmptyMap
import zio.NonEmptyChunk
import model.*
import graviton.HashAlgorithm
import graviton.domain.*
import graviton.core.BinaryKey
import graviton.core.model.BlockLength
import graviton.core.model.Index
import graviton.Hash.given

private final case class AlgoHash(algo: HashAlgorithm, hash: HashBytes)
private given Schema[AlgoHash]                              = DeriveSchema.gen[AlgoHash]
private given Schema[NonEmptyMap[HashAlgorithm, HashBytes]] =
  Schema
    .list[AlgoHash]
    .transformOrFail(
      list => NonEmptyMap.fromIterableOption(list.map(ah => ah.algo -> ah.hash)).toRight("NonEmptyMap cannot be empty"),
      nem => Right(nem.toList.map { case (algo, hash) => AlgoHash(algo, hash) }),
    )

/**
 * Ordered manifest describing how to reassemble a file from blocks.
 */
final case class BlockManifestEntry(
  offset: Index,
  size: BlockLength,
  block: NonEmptyChunk[BinaryKey.CasKey.BlockKey],
)

object BlockManifestEntry:

  given Schema[BlockManifestEntry]                    = DeriveSchema.gen[BlockManifestEntry]
  given Conversion[ManifestEntry, BlockManifestEntry] = (entry: ManifestEntry) =>
    BlockManifestEntry(
      entry.offset,
      entry.size,
      entry.block.map(b => b.toBinaryKey),
    )
end BlockManifestEntry

final case class BlockManifest(
  fileSize: FileSize,
  fileHash: Hash.MultiHash,
  binaryAttributes: BinaryAttributes,
  entries: NonEmptyChunk[BlockManifestEntry],
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
