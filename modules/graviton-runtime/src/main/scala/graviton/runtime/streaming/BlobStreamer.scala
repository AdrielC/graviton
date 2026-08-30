package graviton.runtime.streaming

import graviton.core.keys.BinaryKey
import graviton.core.bytes.Hasher
import graviton.core.model.Block.*
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.stores.{BlockStore, StoreError, StoreOperation}
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

  final case class RangedBlockRef(
    idx: Long,
    key: BinaryKey.Block,
    offset: BlobOffset,
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
    refs: ZStream[Any, StoreError, BlockRef],
    blockStore: BlockStore,
    config: Config = Config(),
  ): ZStream[Any, StoreError, Byte] =
    val window = math.max(1, config.windowRefs)
    val par    = math.max(1, config.maxInFlight)

    // - `buffer(window)` bounds how far ahead we read refs (DB cursor pressure)
    // - `mapZIOPar(par)` prefetches blocks concurrently while preserving manifest order
    // - each block is verified before emission, so corruption never leaks a
    //   partial block to a caller
    // - worst-case memory: par * MaxBlockBytes (default: 2 * 16 MiB = 32 MiB)
    refs
      .buffer(window)
      .mapZIOPar(par)(ref => fetchVerified(ref.key, blockStore))
      .flatMap(block => ZStream.fromChunk(block.bytes))

  /**
   * Reconstruct a half-open byte range while fetching only intersecting
   * blocks. Each selected block remains bounded and is fully verified before
   * any of its requested bytes are emitted.
   */
  def streamRange(
    refs: ZStream[Any, StoreError, RangedBlockRef],
    blockStore: BlockStore,
    start: BlobOffset,
    length: FileSize,
    config: Config = Config(),
  ): ZStream[Any, StoreError, Byte] =
    val requestedStart = start.value
    val requestedEnd   = java.lang.Math.addExact(requestedStart, length.value)
    val window         = math.max(1, config.windowRefs)
    val par            = math.max(1, config.maxInFlight)

    refs
      .buffer(window)
      .mapZIOPar(par)(ref => fetchVerified(ref.key, blockStore).map(ref -> _))
      .flatMap { case (ref, block) =>
        val blockStart = ref.offset.value
        val blockEnd   = java.lang.Math.addExact(blockStart, block.bytes.length.toLong)
        val from       = math.max(requestedStart, blockStart) - blockStart
        val until      = math.min(requestedEnd, blockEnd) - blockStart
        ZStream.fromChunk(block.bytes.slice(from.toInt, until.toInt))
      }

  private def fetchVerified(
    key: BinaryKey.Block,
    blockStore: BlockStore,
  ): IO[StoreError, graviton.core.model.Block] =
    BoundedByteStream
      .collectBlock(blockStore.get(key))
      .mapError(StoreError.fromThrowable(StoreOperation.GetBlock))
      .tap(block => verify(key, block.bytes))

  private def verify(key: BinaryKey.Block, bytes: Chunk[Byte]): IO[StoreError, Unit] =
    for
      _      <- ZIO
                  .fail(StoreError.CorruptData(StoreOperation.GetBlock, s"Block length mismatch for ${key.bits.render}"))
                  .unless(bytes.length.toLong == key.bits.size)
      hasher <- ZIO.fromEither(Hasher.hasher(key.bits.algo)).mapError(StoreError.CorruptData(StoreOperation.GetBlock, _))
      _      <- ZIO.attempt(hasher.update(bytes)).mapError(StoreError.fromThrowable(StoreOperation.GetBlock))
      digest <- ZIO.fromEither(hasher.digest).mapError(StoreError.CorruptData(StoreOperation.GetBlock, _))
      _      <- ZIO
                  .fail(StoreError.CorruptData(StoreOperation.GetBlock, s"Block digest mismatch for ${key.bits.render}"))
                  .unless(digest == key.bits.digest)
    yield ()
