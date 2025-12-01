package graviton

import zio.schema.{DeriveSchema, Schema}
import graviton.core.BinaryKey
import graviton.core.model.BlockSize

import zio.NonEmptyChunk
import graviton.Hash.given
import graviton.Hash.HashResult.given

final case class BlockKey(hash: Hash.SingleHash, size: BlockSize)

enum Condition[+A] extends Product with Serializable:
  case Equals(value: A)             extends Condition[A]
  case NotEquals(value: A)          extends Condition[A]
  case GreaterThan(value: A)        extends Condition[A]
  case GreaterThanOrEqual(value: A) extends Condition[A]
  case LessThan(value: A)           extends Condition[A]
  case LessThanOrEqual(value: A)    extends Condition[A]
end Condition

object Condition:
  given [A: Schema] => Schema[Condition[A]] = DeriveSchema.gen[Condition[A]]
end Condition

final case class BlockKeySelector(
  prefix: Option[NonEmptyChunk[Byte]],
  suffix: Option[NonEmptyChunk[Byte]],
  algorithm: Option[HashAlgorithm],
  size: Option[Condition[BlockSize]],
)

object BlockKeySelector:
  given Schema[BlockKeySelector] = DeriveSchema.gen[BlockKeySelector]
  val any: BlockKeySelector      = BlockKeySelector(
    prefix = None,
    suffix = None,
    algorithm = None,
    size = None,
  )

object BlockKey:
  given Schema[BlockKey]                                = DeriveSchema.gen[BlockKey]
  given Conversion[BinaryKey.CasKey.BlockKey, BlockKey] = (key: BinaryKey.CasKey.BlockKey) => BlockKey(key.hash, key.size)

  extension (key: BlockKey) def toBinaryKey: BinaryKey.CasKey.BlockKey = BinaryKey.CasKey.BlockKey(key.hash, key.size)
