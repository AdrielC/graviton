package graviton.stacked

import graviton.*
import zio.*
import zio.stream.*

final case class StackedBinaryStore(
    primary: BinaryStore,
    replicas: Chunk[BinaryStore]
) extends BinaryStore:
  def put(
      attrs: BinaryAttributes,
      chunkSize: Int
  ): ZSink[Any, Throwable, Byte, Nothing, BinaryId] =
    primary.put(attrs, chunkSize)

  def get(
      id: BinaryId,
      range: Option[ByteRange] = None
  ): IO[Throwable, Option[Bytes]] =
    primary.get(id, range)

  def delete(id: BinaryId): IO[Throwable, Boolean] =
    primary.delete(id)

  def exists(id: BinaryId): IO[Throwable, Boolean] =
    primary.exists(id)
