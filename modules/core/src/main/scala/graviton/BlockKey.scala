package graviton

import zio.schema.{DeriveSchema, Schema}

final case class BlockKey(hash: Hash, size: Int)

final case class BlockKeySelector(prefix: Option[Array[Byte]] = None)

object BlockKey:
  given Schema[BlockKey] = DeriveSchema.gen[BlockKey]
