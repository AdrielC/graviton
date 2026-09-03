package graviton.core.model

import graviton.core.types.{BlockSize, FileSize, MaxBlockBytes}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.{Chunk, NonEmptyChunk}

type UploadChunk = Chunk[Byte] :| UploadChunk.Constraint

object UploadChunk:
  type Constraint = MinLength[1] & MaxLength[MaxBlockBytes]

  inline def maxBytes: Int = ByteConstraints.MaxUploadChunkBytes

  def fromChunk(chunk: Chunk[Byte]): Either[String, UploadChunk] =
    chunk.refineEither[Constraint]

  inline def unsafe(chunk: Chunk[Byte]): UploadChunk =
    chunk.asInstanceOf[UploadChunk]

  extension (chunk: UploadChunk)
    def bytes: Chunk[Byte] = chunk
    def length: Int        = chunk.length

/**
 * Bytes that may be held in memory by a convenience API.
 *
 * Arbitrary-size payloads must stay as streams. This type exists for small,
 * explicitly bounded call sites and keeps the 16 MiB ceiling in the result
 * type instead of relying on documentation alone.
 */
type InMemoryBytes = Chunk[Byte] :| InMemoryBytes.Constraint

object InMemoryBytes:
  type Constraint = MaxLength[MaxBlockBytes]

  inline def maxBytes: Int = ByteConstraints.MaxBlockBytes

  def fromChunk(chunk: Chunk[Byte]): Either[String, InMemoryBytes] =
    chunk.refineEither[Constraint]

  extension (bytes: InMemoryBytes)
    def chunk: Chunk[Byte] = bytes
    def length: Int        = bytes.length

type Block = Chunk[Byte] :| Block.Constraint

object Block:
  type Constraint = MinLength[1] & MaxLength[MaxBlockBytes]

  inline def maxBytes: Int = ByteConstraints.MaxBlockBytes

  def fromChunk(chunk: Chunk[Byte]): Either[String, Block] =
    chunk.refineEither[Constraint]

  inline def unsafe(chunk: Chunk[Byte]): Block =
    chunk.asInstanceOf[Block]

  extension (block: Block)
    def bytes: Chunk[Byte]                 = block
    def nonEmptyBytes: NonEmptyChunk[Byte] = NonEmptyChunk.fromChunk(block).get
    def length: Int                        = block.length
    def blockSize: BlockSize               = BlockSize.unsafe(block.length)
    def fileSize: FileSize                 = FileSize.unsafe(block.length.toLong)

object BlockBuilder:
  def chunkify(bytes: Chunk[Byte], maxBytes: Int = ByteConstraints.MaxBlockBytes): Chunk[Block] =
    if bytes.isEmpty then Chunk.empty
    else
      val iter = bytes.grouped(math.min(maxBytes, ByteConstraints.MaxBlockBytes)).flatMap { group =>
        Block.fromChunk(Chunk.fromIterable(group)).toOption
      }
      Chunk.fromIterator(iter)
