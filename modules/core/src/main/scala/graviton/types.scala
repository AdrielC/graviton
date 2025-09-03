package graviton

import zio.*
import zio.stream.*

// Stream alias used across the API
opaque type Bytes <: ZStream[Any, Throwable, Byte] = ZStream[Any, Throwable, Byte]

opaque type Chunks <: ZStream[Any, Throwable, Chunk[Byte]] = ZStream[Any, Throwable, Chunk[Byte]]

enum HashAlgorithm:
  case SHA256, SHA512, Blake3

final case class Hash(bytes: Chunk[Byte], algo: HashAlgorithm):
  def hex: String = bytes
    .foldLeft(new StringBuilder) { (sb, b) => sb.append(f"$b%02x") }
    .toString

final case class BlockKey(hash: Hash, size: Int)

final case class FileKey(
    hash: Hash,
    algo: HashAlgorithm,
    size: Long,
    mediaType: String
)

opaque type BlobStoreId <: String = String
object BlobStoreId:
  def apply(string: String): BlobStoreId = string.toLowerCase.trim
  def unapply(blobStoreId: BlobStoreId): Option[String] = Some(blobStoreId)


enum BlobStoreStatus:
  case Operational, ReadOnly, Retired

final case class BlockKeySelector(prefix: Option[Array[Byte]] = None)
final case class FileKeySelector(prefix: Option[Array[Byte]] = None)

final case class FileDescriptor(
    key: FileKey,
    blocks: Chunk[BlockKey],
    blockSize: Int
)
final case class FileMetadata(
    filename: Option[String],
    advertisedMediaType: Option[String],
    advertisedLength: Option[Long]
)