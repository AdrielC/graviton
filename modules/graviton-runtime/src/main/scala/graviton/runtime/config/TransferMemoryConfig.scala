package graviton.runtime.config

import graviton.core.RefinedTypeExt
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, LessEqual}
import zio.{Chunk, Config, ZIO, ZLayer}

/** Process-wide ceiling for memory retained by active transfer pipelines. */
type TransferMemoryLimit = TransferMemoryLimit.T
object TransferMemoryLimit extends RefinedTypeExt[Long, GreaterEqual[67108864L] & LessEqual[1099511627776L]]:
  val Default: TransferMemoryLimit = applyUnsafe(512L * 1024L * 1024L)

final case class TransferMemoryConfig(
  maximumBufferedBytes: TransferMemoryLimit = TransferMemoryLimit.Default
)

object TransferMemoryConfig:
  val Default: TransferMemoryConfig = TransferMemoryConfig()

  val config: Config[TransferMemoryConfig] =
    Config
      .long("maximum-buffered-bytes")
      .withDefault(TransferMemoryLimit.Default.value)
      .mapOrFail(value =>
        TransferMemoryLimit
          .either(value)
          .map(TransferMemoryConfig(_))
          .left
          .map(message => Config.Error.InvalidData(Chunk.empty, message))
      )
      .nested("transfer-memory")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, TransferMemoryConfig] =
    ZLayer.fromZIO(ZIO.config(config))
