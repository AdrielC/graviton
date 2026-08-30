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

  sealed trait Error                                   extends Exception
  final case class LimitExceeded(limit: Long)
      extends IllegalArgumentException(s"Byte stream exceeds the in-memory limit of $limit bytes")
      with Error
  final case class InvalidBoundedValue(reason: String) extends IllegalArgumentException(reason) with Error

  /** Collect a block only after enforcing the block bound, then retain it in the refined type. */
  def collectBlock[E](stream: ZStream[Any, E, Byte]): IO[E | Error, Block] =
    collectRaw(stream, Block.maxBytes).flatMap { bytes =>
      ZIO.fromEither(Block.fromChunk(bytes)).mapError(InvalidBoundedValue.apply)
    }

  /** Collect bytes for an explicitly small convenience API. */
  def collectInMemory[E](stream: ZStream[Any, E, Byte]): IO[E | Error, InMemoryBytes] =
    collectRaw(stream, InMemoryBytes.maxBytes).flatMap { bytes =>
      ZIO.fromEither(InMemoryBytes.fromChunk(bytes)).mapError(InvalidBoundedValue.apply)
    }

  /** Collect a JSON or error body under the SDK's fixed 1 MiB control-plane ceiling. */
  def collectControlPlane[E](stream: ZStream[Any, E, Byte]): IO[E | Error, ControlPlaneBytes] =
    collectRaw(stream, MaxControlPlaneBytes).flatMap { bytes =>
      ZIO
        .fromEither(bytes.refineEither[MaxLength[1048576]])
        .mapError(InvalidBoundedValue.apply)
    }

  private def collectRaw[E](stream: ZStream[Any, E, Byte], limit: Int): IO[E | Error, Chunk[Byte]] =
    stream
      .take(limit.toLong + 1L)
      .runCollect
      .flatMap(bytes => ZIO.fail(LimitExceeded(limit.toLong)).when(bytes.length > limit).as(bytes))
