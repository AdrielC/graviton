package graviton.core.model

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric

/**
 * Centralised size/index limits for block and upload handling. Constants align with the
 * legacy values on `main`, ensuring behaviour stays stable while richer wrappers evolve.
 */
object ByteConstraints:

  val MinBlockBytes: Int       = 1
  val MaxBlockBytes: Int       = 16 * 1024 * 1024                // 16 MiB
  val MinUploadChunkBytes: Int = 1
  val MaxUploadChunkBytes: Int = MaxBlockBytes
  val MinFileBytes: Long       = 0L
  val MaxFileBytes: Long       = 1L * 1024 * 1024 * 1024 * 1024L // 1 TiB cap for now

  type BlockSize       = Int :| (numeric.Greater[0] & numeric.LessEqual[16777216])
  type UploadChunkSize = Int :| (numeric.Greater[0] & numeric.LessEqual[16777216])
  type FileSize        = Long :| (numeric.Greater[-1] & numeric.LessEqual[1099511627776L])
  type ChunkCount      = Long :| numeric.Greater[-1]
  type ChunkIndex      = Long :| numeric.Greater[-1]
  type BlockIndex      = Long :| numeric.Greater[-1]

  def refineBlockSize(value: Int): Either[String, BlockSize] =
    if value <= 0 then Left(s"Block size must be positive, got $value")
    else if value > MaxBlockBytes then Left(s"Block size exceeds $MaxBlockBytes bytes (got $value)")
    else Right(value.asInstanceOf[BlockSize])

  def refineUploadChunkSize(value: Int): Either[String, UploadChunkSize] =
    if value <= 0 then Left(s"Chunk size must be positive, got $value")
    else if value > MaxUploadChunkBytes then Left(s"Chunk size exceeds $MaxUploadChunkBytes bytes (got $value)")
    else Right(value.asInstanceOf[UploadChunkSize])

  def refineFileSize(value: Long): Either[String, FileSize] =
    if value < 0 then Left(s"File size cannot be negative, got $value")
    else if value > MaxFileBytes then Left(s"File size exceeds $MaxFileBytes bytes (got $value)")
    else Right(value.asInstanceOf[FileSize])

  def refineChunkCount(value: Long): Either[String, ChunkCount] =
    if value < 0 then Left(s"Chunk count cannot be negative, got $value")
    else Right(value.asInstanceOf[ChunkCount])

  def refineBlockIndex(value: Long): Either[String, BlockIndex] =
    if value < 0 then Left(s"Block index cannot be negative, got $value")
    else Right(value.asInstanceOf[BlockIndex])

  inline def unsafeBlockSize(value: Int): BlockSize = value.asInstanceOf[BlockSize]
  inline def unsafeFileSize(value: Long): FileSize  = value.asInstanceOf[FileSize]

end ByteConstraints

type BlockSize       = ByteConstraints.BlockSize
type UploadChunkSize = ByteConstraints.UploadChunkSize
type FileSize        = ByteConstraints.FileSize
type ChunkCount      = ByteConstraints.ChunkCount
type ChunkIndex      = ByteConstraints.ChunkIndex
type BlockIndex      = ByteConstraints.BlockIndex
