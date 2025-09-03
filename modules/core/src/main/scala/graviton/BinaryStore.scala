package graviton

import graviton.core.BinaryAttributes
import zio.*
import zio.stream.*

trait BinaryStore:
  def put(
      attrs: BinaryAttributes,
      chunkSize: Int
  ): ZSink[Any, Throwable, Byte, Nothing, BinaryId]
  def get(
      id: BinaryId,
      range: Option[ByteRange] = None
  ): IO[Throwable, Option[Bytes]]
  def delete(id: BinaryId): IO[Throwable, Boolean]
  def exists(id: BinaryId): IO[Throwable, Boolean]
