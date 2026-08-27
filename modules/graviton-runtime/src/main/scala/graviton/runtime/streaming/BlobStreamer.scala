package graviton.runtime.streaming

import graviton.core.keys.BinaryKey
import graviton.core.bytes.Hasher
import graviton.core.model.Block.*
import graviton.runtime.stores.BlockStore
import graviton.streams.BoundedByteStream
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
    maxInFlight: Int = 2,
  ):
    require(windowRefs > 0, "windowRefs must be positive")
    require(maxInFlight > 0, "maxInFlight must be positive")

    /** Maximum bytes held by ordered block prefetch, excluding backend I/O chunks. */
    def maximumPrefetchedBytes: Long = maxInFlight.toLong * graviton.core.model.Block.maxBytes.toLong

  def streamBlob(
    refs: ZStream[Any, Throwable, BlockRef],
    blockStore: BlockStore,
    config: Config = Config(),
  ): ZStream[Any, Throwable, Byte] =
    val window = math.max(1, config.windowRefs)
    val par    = math.max(1, config.maxInFlight)

    // - `buffer(window)` bounds how far ahead we read refs (DB cursor pressure)
    // - `mapZIOPar(par)` prefetches blocks concurrently while preserving manifest order
    // - each block is verified before emission, so corruption never leaks a
    //   partial block to a caller
    // - worst-case memory: par * MaxBlockBytes (default: 2 * 16 MiB = 32 MiB)
    refs
      .buffer(window)
      .mapZIOPar(par)(ref =>
        BoundedByteStream
          .collectBlock(blockStore.get(ref.key))
          .tap(block => verify(ref.key, block.bytes))
      )
      .flatMap(block => ZStream.fromChunk(block.bytes))

  private def verify(key: BinaryKey.Block, bytes: Chunk[Byte]): Task[Unit] =
    for
      _      <- ZIO
                  .fail(new IllegalStateException(s"Block length mismatch for ${key.bits.render}"))
                  .unless(bytes.length.toLong == key.bits.size)
      hasher <- ZIO.fromEither(Hasher.hasher(key.bits.algo)).mapError(new IllegalArgumentException(_))
      _      <- ZIO.attempt(hasher.update(bytes))
      digest <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
      _      <- ZIO
                  .fail(new IllegalStateException(s"Block digest mismatch for ${key.bits.render}"))
                  .unless(digest == key.bits.digest)
    yield ()
