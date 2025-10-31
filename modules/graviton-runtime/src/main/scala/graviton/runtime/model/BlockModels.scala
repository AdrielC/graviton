package graviton.runtime.model

import graviton.core.attributes.BinaryAttributes
import graviton.core.keys.BinaryKey
import zio.Chunk

final case class CanonicalBlock(
  key: BinaryKey.Block,
  bytes: Chunk[Byte],
  attributes: BinaryAttributes = BinaryAttributes.empty,
)

enum BlockStoredStatus derives CanEqual:
  case Fresh
  case Duplicate
  case Forwarded

final case class StoredBlock(
  key: BinaryKey.Block,
  size: Long,
  status: BlockStoredStatus,
)

final case class BlockBatchResult(
  stored: Chunk[StoredBlock],
  forward: Chunk[CanonicalBlock] = Chunk.empty,
)

final case class BlockWritePlan(
  forwardDuplicates: Boolean = false
)
