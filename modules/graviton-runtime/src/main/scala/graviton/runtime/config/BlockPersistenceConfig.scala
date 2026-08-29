package graviton.runtime.config

import graviton.core.types.BlockWriteParallelism
import zio.{Chunk, Config}

/** Bounded concurrency for persisting already-hashed CAS blocks. */
final case class BlockPersistenceConfig(
  parallelism: BlockWriteParallelism = BlockPersistenceConfig.DefaultParallelism
)

object BlockPersistenceConfig:
  val SequentialParallelism: BlockWriteParallelism = BlockWriteParallelism.applyUnsafe(1)
  val DefaultParallelism: BlockWriteParallelism    = BlockWriteParallelism.applyUnsafe(4)

  val sequential: BlockPersistenceConfig = BlockPersistenceConfig(SequentialParallelism)
  val default: BlockPersistenceConfig    = BlockPersistenceConfig(DefaultParallelism)

  val config: Config[BlockPersistenceConfig] =
    Config
      .int("block-write-parallelism")
      .withDefault(DefaultParallelism.value)
      .mapOrFail(value =>
        BlockWriteParallelism
          .either(value)
          .map(BlockPersistenceConfig(_))
          .left
          .map(message => Config.Error.InvalidData(Chunk.empty, message))
      )
      .nested("graviton")
