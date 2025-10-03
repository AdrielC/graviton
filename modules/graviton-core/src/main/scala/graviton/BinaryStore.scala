package graviton

import graviton.ranges.ByteRange
import zio.*
import zio.stream.*

trait BinaryStore:
  def put: ZSink[Any, Throwable, Byte, Nothing, BinaryId]
  def get(
    id: BinaryId,
    range: Option[ByteRange] = None,
  ): IO[Throwable, Option[Bytes]]
  def delete(id: BinaryId): IO[Throwable, Boolean]
  def exists(id: BinaryId): IO[Throwable, Boolean]
