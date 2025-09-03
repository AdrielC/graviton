package graviton

import zio.*
import zio.stream.*

// Stream alias used across the API
type Bytes = ZStream[Any, Throwable, Byte]

enum HashAlgorithm:
  case SHA256, SHA512, Blake3

final case class Hash(bytes: Chunk[Byte], algo: HashAlgorithm):
  def hex: String = bytes.foldLeft(new StringBuilder) { (sb, b) => sb.append(f"$b%02x") }.toString

final case class BlockKey(hash: Hash, size: Int)

final case class FileKey(hash: Hash, algo: HashAlgorithm, size: Long, mediaType: String)

final case class BlobStoreId(value: String) extends AnyVal

enum BlobStoreStatus:
  case Operational, ReadOnly, Retired

final case class BlockKeySelector(prefix: Option[Array[Byte]] = None)
final case class FileKeySelector(prefix: Option[Array[Byte]] = None)

final case class FileDescriptor(key: FileKey, blocks: Chunk[BlockKey], blockSize: Int)
final case class FileMetadata(filename: Option[String], advertisedMediaType: Option[String])

sealed trait GravitonError extends Throwable
object GravitonError:
  final case class NotFound(msg: String) extends Exception(msg) with GravitonError
  final case class BackendUnavailable(msg: String) extends Exception(msg) with GravitonError
  final case class CorruptData(msg: String) extends Exception(msg) with GravitonError
  final case class PolicyViolation(msg: String) extends Exception(msg) with GravitonError
