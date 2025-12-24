package graviton.core.model

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric
import zio.Chunk
import scodec.bits.ByteVector
import graviton.core.bytes.Hasher
import graviton.core.bytes.Digest

/**
 * Centralised size/index limits for block and upload handling. Constants align with the
 * legacy values on `main`, ensuring behaviour stays stable while richer wrappers evolve.
 */
object ByteConstraints:

  val MinBlockBytes: Int       = 1
  val MaxBlockBytes: Int       = 16 * 1024 * 1024 // 16 MiB
  val MinUploadChunkBytes: Int = 1
  val MaxUploadChunkBytes: Int = MaxBlockBytes
  val MinFileBytes: Long       = 0L

  type BlockSize = Int :| (numeric.Greater[0] & numeric.LessEqual[16777216])
  object BlockSize:
    type Constraint = numeric.Greater[0] & numeric.LessEqual[16777216]
    def either(value: Int): Either[String, BlockSize] = value.refineEither[Constraint]
    inline def unsafe(value: Int): BlockSize          = value.asInstanceOf[BlockSize]

    def unapply(value: String): Option[BlockSize] =
      scala.util.Try(value.toInt).toOption.flatMap(either(_).toOption)

  type UploadChunkSize = Int :| (numeric.Greater[0] & numeric.LessEqual[16777216])
  type FileSize        = Long :| numeric.Greater[-1]
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
    if value < MinFileBytes then Left(s"File size cannot be negative, got $value")
    else Right(value.asInstanceOf[FileSize])

  /**
   * Enforce a backend defined limit on a file/blob size. Limits vary per store (filesystem, S3,
   * database LOBs, etc) so we keep the refinement dynamic and let configs call this helper.
   */
  def enforceFileLimit(value: Long, maxBytes: Long): Either[String, FileSize] =
    if value > maxBytes then Left(s"File size exceeds backend limit $maxBytes bytes (got $value)")
    else refineFileSize(value)

  def refineChunkCount(value: Long): Either[String, ChunkCount] =
    if value < 0 then Left(s"Chunk count cannot be negative, got $value")
    else Right(value.asInstanceOf[ChunkCount])

  def refineBlockIndex(value: Long): Either[String, BlockIndex] =
    if value < 0 then Left(s"Block index cannot be negative, got $value")
    else Right(value.asInstanceOf[BlockIndex])

  inline def unsafeBlockSize(value: Int): BlockSize = value.asInstanceOf[BlockSize]
  inline def unsafeFileSize(value: Long): FileSize  = value.refineUnsafe[numeric.Greater[-1]]

end ByteConstraints

type BlockSize = ByteConstraints.BlockSize
val BlockSize = ByteConstraints.BlockSize
type UploadChunkSize = ByteConstraints.UploadChunkSize
type FileSize        = ByteConstraints.FileSize
type ChunkCount      = ByteConstraints.ChunkCount
type ChunkIndex      = ByteConstraints.ChunkIndex
type BlockIndex      = ByteConstraints.BlockIndex
