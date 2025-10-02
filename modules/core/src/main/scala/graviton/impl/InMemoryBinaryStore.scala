package graviton.impl

import graviton.*
import zio.*
import zio.stream.*

final class InMemoryBinaryStore private (ref: Ref[Map[BinaryId, Chunk[Byte]]]) extends BinaryStore:

  def put: ZSink[Any, Throwable, Byte, Nothing, BinaryId] =
    ZSink.collectAll[Byte].mapZIO { data =>
      val id = BinaryId(java.util.UUID.randomUUID().toString)
      ref.update(_ + (id -> data)).as(id)
    }

  def get(
    id: BinaryId,
    range: Option[ByteRange] = None,
  ): IO[Throwable, Option[Bytes]] =
    ref.get
      .map(_.get(id))
      .map(_.map { bytes =>
        val sliced = range.fold(bytes) { r =>
          val start = r.startLong.toInt
          val end   = math.min(r.endExclusiveLong.toInt, bytes.size)
          bytes.slice(start, end)
        }
        Bytes(ZStream.fromChunk(sliced))
      })

  def delete(id: BinaryId): IO[Throwable, Boolean] =
    ref.modify { m =>
      m.get(id) match
        case None    => (false, m)
        case Some(_) => (true, m - id)
    }

  def exists(id: BinaryId): IO[Throwable, Boolean] =
    ref.get.map(_.contains(id))

object InMemoryBinaryStore:
  def make(): UIO[BinaryStore] =
    Ref.make(Map.empty[BinaryId, Chunk[Byte]]).map(new InMemoryBinaryStore(_))
