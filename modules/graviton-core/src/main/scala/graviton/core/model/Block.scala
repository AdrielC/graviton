package graviton.core.model

import zio.{Chunk, NonEmptyChunk}

opaque type UploadChunk = Chunk[Byte]

object UploadChunk:
  inline def maxBytes: Int = ByteConstraints.MaxUploadChunkBytes

  def fromChunk(chunk: Chunk[Byte]): Either[String, UploadChunk] =
    if chunk.isEmpty then Left("Upload chunk cannot be empty")
    else if chunk.length > ByteConstraints.MaxUploadChunkBytes then
      Left(s"Upload chunk exceeds ${ByteConstraints.MaxUploadChunkBytes} bytes (got ${chunk.length})")
    else Right(chunk)

  inline def unsafe(chunk: Chunk[Byte]): UploadChunk = chunk

  extension (chunk: UploadChunk)
    def bytes: Chunk[Byte] = chunk
    def length: Int        = chunk.length

opaque type Block = Chunk[Byte]

object Block:
  inline def maxBytes: Int = ByteConstraints.MaxBlockBytes

  def fromChunk(chunk: Chunk[Byte]): Either[String, Block] =
    if chunk.isEmpty then Left("Block cannot be empty")
    else if chunk.length > ByteConstraints.MaxBlockBytes then
      Left(s"Block exceeds ${ByteConstraints.MaxBlockBytes} bytes (got ${chunk.length})")
    else Right(chunk)

  inline def unsafe(chunk: Chunk[Byte]): Block = chunk

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
