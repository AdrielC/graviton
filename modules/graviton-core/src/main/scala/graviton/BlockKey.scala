package graviton

import graviton.core.model.Size
import zio.schema.{DeriveSchema, Schema}

final case class BlockKey(hash: Hash, size: Size)

final case class BlockKeySelector(prefix: Option[Array[Byte]] = None)

object BlockKey:
  given Schema[BlockKey] = DeriveSchema.gen[BlockKey]
