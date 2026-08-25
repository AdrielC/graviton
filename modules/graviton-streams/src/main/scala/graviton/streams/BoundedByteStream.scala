package graviton.streams

import graviton.core.model.{Block, InMemoryBytes}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MaxLength
import zio.*
import zio.stream.ZStream

/** Explicitly bounded collection for the few domains that permit bytes in memory. */
object BoundedByteStream:

  type ControlPlaneBytes = Chunk[Byte] :| MaxLength[1048576]

  val MaxControlPlaneBytes: Int = 1024 * 1024

  final case class LimitExceeded(limit: Long) extends IllegalArgumentException(s"Byte stream exceeds the in-memory limit of $limit bytes")

  /** Collect a block only after enforcing the block bound, then retain it in the refined type. */
  def collectBlock(stream: ZStream[Any, Throwable, Byte]): Task[Block] =
    collectRaw(stream, Block.maxBytes).flatMap { bytes =>
      ZIO.fromEither(Block.fromChunk(bytes)).mapError(new IllegalArgumentException(_))
    }

  /** Collect bytes for an explicitly small convenience API. */
  def collectInMemory(stream: ZStream[Any, Throwable, Byte]): Task[InMemoryBytes] =
    collectRaw(stream, InMemoryBytes.maxBytes).flatMap { bytes =>
      ZIO.fromEither(InMemoryBytes.fromChunk(bytes)).mapError(new IllegalArgumentException(_))
    }

  /** Collect a JSON or error body under the SDK's fixed 1 MiB control-plane ceiling. */
  def collectControlPlane(stream: ZStream[Any, Throwable, Byte]): Task[ControlPlaneBytes] =
    collectRaw(stream, MaxControlPlaneBytes).flatMap { bytes =>
      ZIO
        .fromEither(bytes.refineEither[MaxLength[1048576]])
        .mapError(new IllegalArgumentException(_))
    }

  private def collectRaw(stream: ZStream[Any, Throwable, Byte], limit: Int): Task[Chunk[Byte]] =
    stream
      .take(limit.toLong + 1L)
      .runCollect
      .flatMap(bytes => ZIO.fail(LimitExceeded(limit.toLong)).when(bytes.length > limit).as(bytes))
