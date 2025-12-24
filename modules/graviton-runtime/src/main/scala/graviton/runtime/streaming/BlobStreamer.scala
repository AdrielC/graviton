package graviton.runtime.streaming

import graviton.core.keys.BinaryKey
import graviton.runtime.stores.BlockStore
import zio.*
import zio.stream.*

/**
 * Streams a blob by:
 * - streaming ordered block refs (typically from Postgres)
 * - fetching blocks in parallel (bounded)
 * - emitting bytes strictly in ref order
 *
 * This is the “DB streams keys; ZIO streams bytes” composition layer.
 *
 * Safe defaults assume blocks are bounded (<= MaxBlockBytes) so buffering whole blocks is acceptable
 * as long as `windowRefs` stays small.
 */
object BlobStreamer:

  final case class BlockRef(
    idx: Long,
    key: BinaryKey.Block,
  )

  final case class Config(
    windowRefs: Int = 64,
    maxInFlight: Int = 8,
  )

  def streamBlob(
    refs: ZStream[Any, Throwable, BlockRef],
    blockStore: BlockStore,
    config: Config = Config(),
  ): ZStream[Any, Throwable, Byte] =
    val window = math.max(1, config.windowRefs)
    val par    = math.max(1, config.maxInFlight)

    // For bounded-size blocks, we can keep the implementation simple and robust:
    // - `buffer(window)` bounds how far ahead we read refs (DB cursor pressure)
    // - `mapZIOPar(par)` bounds block fetch concurrency
    // - output remains in manifest order (mapZIOPar preserves input order)
    refs
      .buffer(window)
      .mapZIOPar(par)(ref => blockStore.get(ref.key).runCollect)
      .flattenChunks
