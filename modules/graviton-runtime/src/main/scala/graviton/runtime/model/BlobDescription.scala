package graviton.runtime.model

import graviton.core.keys.BinaryKey
import zio.Chunk

/** Durable blob metadata returned by a storage inventory query. */
final case class BlobListing(
  key: BinaryKey.Blob,
  stat: BlobStat,
  blockCount: Int,
)

/** One persisted block reference from a blob manifest. */
final case class BlobBlockDescription(
  index: Long,
  key: BinaryKey.Block,
  offset: Long,
  size: Long,
)

/** A blob inventory row plus its persisted manifest layout. */
final case class BlobDescription(
  listing: BlobListing,
  blocks: Chunk[BlobBlockDescription],
)
