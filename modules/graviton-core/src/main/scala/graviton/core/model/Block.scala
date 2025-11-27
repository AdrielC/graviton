package graviton.core.model

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.{Chunk, NonEmptyChunk}

type UploadChunk = Chunk[Byte] :| UploadChunk.Constraint

object UploadChunk:
  type Constraint = MinLength[1] & MaxLength[16777216]

  inline def maxBytes: Int = ByteConstraints.MaxUploadChunkBytes

  def fromChunk(chunk: Chunk[Byte]): Either[String, UploadChunk] =
    chunk.refineEither[Constraint]

  inline def unsafe(chunk: Chunk[Byte]): UploadChunk =
    chunk.asInstanceOf[UploadChunk]

  extension (chunk: UploadChunk)
    def bytes: Chunk[Byte] = chunk
    def length: Int        = chunk.length

type Block = Chunk[Byte] :| Block.Constraint

object Block:
  type Constraint = MinLength[1] & MaxLength[16777216]

  inline def maxBytes: Int = ByteConstraints.MaxBlockBytes

  def fromChunk(chunk: Chunk[Byte]): Either[String, Block] =
    chunk.refineEither[Constraint]

  inline def unsafe(chunk: Chunk[Byte]): Block =
    chunk.asInstanceOf[Block]

  extension (block: Block)
    def bytes: Chunk[Byte]                 = block
    def nonEmptyBytes: NonEmptyChunk[Byte] = NonEmptyChunk.fromChunk(block).get
    def length: Int                        = block.length
    def blockSize: BlockSize               = ByteConstraints.unsafeBlockSize(block.length)
    def fileSize: FileSize                 = ByteConstraints.unsafeFileSize(block.length.toLong)

object BlockBuilder:
  def chunkify(bytes: Chunk[Byte], maxBytes: Int = ByteConstraints.MaxBlockBytes): Chunk[Block] =
    if bytes.isEmpty then Chunk.empty
    else
      val iter = bytes.grouped(math.min(maxBytes, ByteConstraints.MaxBlockBytes)).flatMap { group =>
        Block.fromChunk(Chunk.fromIterable(group)).toOption
      }
      Chunk.fromIterator(iter)
