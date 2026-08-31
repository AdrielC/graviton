package graviton.runtime.streaming

import graviton.core.keys.BinaryKey
import graviton.core.bytes.Hasher
import graviton.core.model.Block.*
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.stores.{BlockStore, StoreError, StoreOperation}
import graviton.streams.BoundedByteStream
import graviton.core.RefinedTypeExt
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, LessEqual}
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

  private val OrderedResultBuffer = 1

  /**
   * ZIO Streams' ordered parallel mapper can retain the element currently
   * awaited or emitted, the bounded downstream queue, and one producer fiber
   * blocked while offering to that queue. Keep this formula conservative and
   * in lockstep with the explicit `bufferSize` passed below.
   */
  private val MaximumRetainedBlocks = OrderedResultBuffer + 2

  type ReferenceWindow = ReferenceWindow.T
  object ReferenceWindow extends RefinedTypeExt[Int, GreaterEqual[1] & LessEqual[4096]]:
    val Default: ReferenceWindow = applyUnsafe(64)

  type FetchParallelism = FetchParallelism.T
  object FetchParallelism extends RefinedTypeExt[Int, GreaterEqual[1] & LessEqual[16]]:
    val Default: FetchParallelism = applyUnsafe(2)

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
    windowRefs: ReferenceWindow = ReferenceWindow.Default,
    maxInFlight: FetchParallelism = FetchParallelism.Default,
  ):
    /** Maximum bytes held by ordered block prefetch, excluding backend I/O chunks. */
    def maximumPrefetchedBytes: Long = MaximumRetainedBlocks.toLong * graviton.core.model.Block.maxBytes.toLong

  object Config:
    val config: zio.Config[Config] =
      (zio.Config.int("window-refs").withDefault(64) ++
        zio.Config.int("max-in-flight").withDefault(2))
        .mapOrFail { case (windowRefs, maxInFlight) =>
          (for
            window   <- ReferenceWindow.either(windowRefs)
            parallel <- FetchParallelism.either(maxInFlight)
            _        <- Either.cond(parallel.value <= window.value, (), "download max-in-flight must not exceed window-refs")
          yield Config(window, parallel)).left.map(message => zio.Config.Error.InvalidData(Chunk.empty, message))
        }
        .nested("download")
        .nested("graviton")

  def streamBlob(
    refs: ZStream[Any, StoreError, BlockRef],
    blockStore: BlockStore,
    config: Config = Config(),
  ): ZStream[Any, StoreError, Byte] =
    val window = config.windowRefs.value
    val par    = config.maxInFlight.value

    // - `buffer(window)` bounds how far ahead we read refs (DB cursor pressure)
    // - ordered `mapZIOPar` prefetches blocks concurrently while preserving manifest order
    // - its result queue is explicitly one slot; never inherit ZIO's larger default
    // - each block is verified before emission, so corruption never leaks a
    //   partial block to a caller
    // - conservative retained-output bound: 3 * MaxBlockBytes = 48 MiB
    refs
      .buffer(window)
      .mapZIOPar(par, bufferSize = OrderedResultBuffer)(ref => fetchVerified(ref.key, blockStore))
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
    val window         = config.windowRefs.value
    val par            = config.maxInFlight.value

    refs
      .buffer(window)
      .mapZIOPar(par, bufferSize = OrderedResultBuffer)(ref => fetchVerified(ref.key, blockStore).map(ref -> _))
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
