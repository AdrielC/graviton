package torrent

import zio.schema.{ DeriveSchema, Schema }

case class FileChunk(
  data:   Bytes,
  offset: Index,
  key:    BinaryKey
):
  def size: Length   = data.getSize
  def length: Length = size

object FileChunk:
  given Schema[FileChunk] = DeriveSchema.gen[FileChunk]
