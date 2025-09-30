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
        val sliced = range match
          case Some(ByteRange(start, endExclusive)) =>
            val s = start.toInt
            val e = math.min(endExclusive.toInt, bytes.size)
            bytes.slice(s, e)
          case None                                 => bytes
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
