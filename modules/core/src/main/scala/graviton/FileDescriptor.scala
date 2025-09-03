package graviton

import zio.Chunk

final case class FileDescriptor(
    key: FileKey,
    blocks: Chunk[BlockKey],
    blockSize: Int
)

final case class FileMetadata(
    filename: Option[String],
    advertisedMediaType: Option[String]
)
