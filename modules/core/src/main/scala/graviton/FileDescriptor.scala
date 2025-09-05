package graviton

import zio.Chunk
import zio.schema.{DeriveSchema, Schema}

final case class FileDescriptor(
  key: FileKey,
  blocks: Chunk[BlockKey],
  blockSize: Int,
)

object FileDescriptor:
  val SchemaVersion            = 1
  given Schema[FileDescriptor] = DeriveSchema.gen[FileDescriptor]
