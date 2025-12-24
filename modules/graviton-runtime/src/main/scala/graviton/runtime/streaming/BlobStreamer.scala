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
    ZStream.unwrapScoped {
      val window = math.max(1, config.windowRefs)
      val par    = math.max(1, config.maxInFlight)

      for
        refsQ <- Queue.bounded[BlockRef](window)
        // Completed blocks (out of order). Bounded to cap how much buffered data can pile up.
        doneQ <- Queue.bounded[Option[(Long, Chunk[Byte])]](window)
        outQ  <- Queue.bounded[Chunk[Byte]](window)

        // Stage A: DB producer (push refs into bounded queue)
        _ <- refs
               .tap(refsQ.offer)
               .ensuring(refsQ.shutdown)
               .runDrain
               .forkScoped

        // Stage B1: bounded parallel fetch (store results into doneQ)
        _ <- ZStream
               .fromQueue(refsQ)
               .mapZIOParUnordered(par) { ref =>
                 blockStore.get(ref.key).runCollect.map(bytes => (ref.idx, bytes))
               }
               .tap(res => doneQ.offer(Some(res)))
               .ensuring(doneQ.offer(None).ignore)
               .runDrain
               .forkScoped

        // Stage B2: ordering coordinator (doneQ -> outQ)
        _ <- coordinator(doneQ, outQ).forkScoped
      yield ZStream.fromQueue(outQ).flattenChunks
    }

  private def coordinator(
    doneQ: Queue[Option[(Long, Chunk[Byte])]],
    outQ: Queue[Chunk[Byte]],
  ): ZIO[Scope, Throwable, Unit] =
    Ref
      .make(0L)
      .flatMap { nextRef =>
        Ref.make(Map.empty[Long, Chunk[Byte]]).flatMap { bufRef =>
          def emitReady: UIO[Unit] =
            for
              next <- nextRef.get
              buf  <- bufRef.get
              _    <- buf.get(next) match
                        case None        => ZIO.unit
                        case Some(bytes) =>
                          outQ.offer(bytes) *>
                            bufRef.update(_ - next) *>
                            nextRef.update(_ + 1) *>
                            emitReady
            yield ()

          def loop: ZIO[Any, Throwable, Unit] =
            emitReady *>
              doneQ.take.flatMap {
                case None =>
                  // No more completions are coming. Flush what we can and end.
                  emitReady *> outQ.shutdown

                case Some((idx, bytes)) =>
                  bufRef.update(_ + (idx -> bytes)) *> loop
              }

          loop
        }
      }
      .uninterruptible
