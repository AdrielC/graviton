package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.model.*
import zio.*
import zio.stream.*

import scala.collection.immutable.Map

final class InMemoryBlockStore private (
  state: Ref[Map[BinaryKey.Block, CanonicalBlock]]
) extends BlockStore:

  override def putBlocks(plan: BlockWritePlan = BlockWritePlan()): BlockSink =
    val initial = Acc(
      entries = ChunkBuilder.make[BlockManifestEntry](),
      stored = ChunkBuilder.make[StoredBlock](),
      forwarded = ChunkBuilder.make[CanonicalBlock](),
      frames = ChunkBuilder.make[BlockFrame](),
      offset = 0L,
      index = 0L,
    )

    ZSink
      .foldZIO(initial)(_ => true) { (acc, block: CanonicalBlock) =>
        for
          entry  <- ZIO
                      .fromEither(BlockManifestEntry.make(acc.index, acc.offset, block.key, block.bytes.length))
                      .mapError(msg => new IllegalArgumentException(msg))
          status <- storeBlock(block)
          frame  <- synthesizeFrame(status, block, acc.index, plan)
          _       = acc.entries += entry
          _       = acc.stored += StoredBlock(block.key, block.size, status)
          _       = if status == BlockStoredStatus.Duplicate && plan.forwardDuplicates then acc.forwarded += block
          _       = frame.foreach(acc.frames += _)
        yield acc.copy(
          offset = acc.offset + block.normalizedSize.toLong,
          index = acc.index + 1,
        )
      }
      .mapZIO { acc =>
        ZIO
          .fromEither(BlockManifest.build(acc.entries.result()))
          .mapError(msg => new IllegalArgumentException(msg))
          .map { manifest =>
            BlockBatchResult(
              manifest = manifest,
              stored = acc.stored.result(),
              forward = acc.forwarded.result(),
              frames = acc.frames.result(),
            )
          }
      }
      .ignoreLeftover

  override def get(key: BinaryKey.Block): ZStream[Any, Throwable, Byte] =
    ZStream
      .fromZIO(
        state.get.flatMap { map =>
          ZIO
            .fromOption(map.get(key))
            .mapError(_ => new NoSuchElementException(s"Block ${key.bits.digest.value} not found"))
        }
      )
      .flatMap(block => ZStream.fromChunk(block.bytes))

  override def exists(key: BinaryKey.Block): ZIO[Any, Throwable, Boolean] =
    state.get.map(_.contains(key))

  private def storeBlock(block: CanonicalBlock): UIO[BlockStoredStatus] =
    state.modify { current =>
      if current.contains(block.key) then BlockStoredStatus.Duplicate -> current
      else BlockStoredStatus.Fresh                                    -> current.updated(block.key, block)
    }

  private def synthesizeFrame(
    status: BlockStoredStatus,
    block: CanonicalBlock,
    index: Long,
    plan: BlockWritePlan,
  ): IO[Throwable, Option[BlockFrame]] =
    if status != BlockStoredStatus.Fresh then ZIO.succeed(None)
    else
      plan.frame.layout match
        case FrameLayout.BlockPerFrame =>
          ZIO
            .fromEither(BlockFramer.synthesizeBlock(block, index, plan, FrameContext()))
            .mapError(msg => new IllegalArgumentException(msg))
            .map(Some(_))
        case FrameLayout.Aggregate(_)  =>
          ZIO.fail(new UnsupportedOperationException("InMemoryBlockStore does not support aggregate frame layout yet"))

private final case class Acc(
  entries: ChunkBuilder[BlockManifestEntry],
  stored: ChunkBuilder[StoredBlock],
  forwarded: ChunkBuilder[CanonicalBlock],
  frames: ChunkBuilder[BlockFrame],
  offset: Long,
  index: Long,
)

object InMemoryBlockStore:
  def make: UIO[InMemoryBlockStore] =
    Ref.make(Map.empty[BinaryKey.Block, CanonicalBlock]).map(ref => new InMemoryBlockStore(ref))

  val layer: ULayer[BlockStore] =
    ZLayer.fromZIO(make)
