package graviton.core

import graviton.core.model.{
  ByteConstraints,
  BlockIndex as ModelBlockIndex,
  BlockSize as ModelBlockSize,
  ChunkCount as ModelChunkCount,
  ChunkIndex as ModelChunkIndex,
  FileSize as ModelFileSize,
  UploadChunkSize as ModelChunkSize,
}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric
import zio.schema.Schema

import java.util.regex.Pattern

object types:
  type Algo             = String :| Match["(sha-256|sha-1|blake3|md5)"]
  type HexLower         = String :| (Match["[0-9a-f]+"] & MinLength[2])
  type Mime             = String :| Match["[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+(;.*)?"]
  type Size             = Long :| numeric.Greater[-1]
  type FileSize         = ModelFileSize
  type ChunkSize        = ModelChunkSize
  type ChunkIndex       = ModelChunkIndex
  type ChunkCount       = ModelChunkCount
  type BlockSize        = ModelBlockSize
  type BlockIndex       = ModelBlockIndex
  type CompressionLevel = Int :| (numeric.Greater[-1] & numeric.LessEqual[22])
  type KekId            = String :| (Match["[A-Za-z0-9:_-]{4,128}"] & MinLength[4])
  type NonceLength      = Int :| (numeric.Greater[0] & numeric.LessEqual[32])
  type LocatorScheme    = String :| Match["[a-z0-9+.-]+"]
  type PathSegment      = String :| (Match["[^/]+"] & MinLength[1])
  type FileSegment      = String :| (Match["[^/]+"] & MinLength[1])

  val MaxBlockBytes: Int = ByteConstraints.MaxBlockBytes

  private val Sha256HexLength = 64
  private val Sha1HexLength   = 40
  private val Md5HexLength    = 32
  private val KekIdPattern    = Pattern.compile("^[A-Za-z0-9:_-]{4,128}$")

  def validateDigest(algo: Algo, hex: HexLower): Either[String, Unit] =
    algo match
      case "sha-256" =>
        Either.cond(hex.length == Sha256HexLength, (), s"sha-256 requires $Sha256HexLength hex chars, got ${hex.length}")
      case "sha-1"   =>
        Either.cond(hex.length == Sha1HexLength, (), s"sha-1 requires $Sha1HexLength hex chars, got ${hex.length}")
      case "md5"     =>
        Either.cond(hex.length == Md5HexLength, (), s"md5 requires $Md5HexLength hex chars, got ${hex.length}")
      case "blake3"  => Right(())
      case other     => Left(s"Unknown digest algorithm: $other")

  given Schema[Size] =
    Schema[Long].transformOrFail(
      value =>
        if value >= 0 then Right(value.asInstanceOf[Size])
        else Left(s"Size must be ? 0, got $value"),
      refined => Right(refined.asInstanceOf[Long]),
    )

  given Schema[BlockIndex] =
    Schema[Long].transformOrFail(
      value => ByteConstraints.refineBlockIndex(value),
      refined => Right(refined.asInstanceOf[Long]),
    )

  given Schema[BlockSize] =
    Schema[Int].transformOrFail(
      value => ByteConstraints.refineBlockSize(value),
      refined => Right(refined.asInstanceOf[Int]),
    )

  given Schema[FileSize] =
    Schema[Long].transformOrFail(
      value => ByteConstraints.refineFileSize(value),
      refined => Right(refined.asInstanceOf[Long]),
    )

  given Schema[ChunkCount] =
    Schema[Long].transformOrFail(
      value => ByteConstraints.refineChunkCount(value),
      refined => Right(refined.asInstanceOf[Long]),
    )

  given Schema[CompressionLevel] =
    Schema[Int].transformOrFail(
      value =>
        if value < -1 || value > 22 then Left(s"Compression level must be between -1 and 22, got $value")
        else Right(value.asInstanceOf[CompressionLevel]),
      refined => Right(refined.asInstanceOf[Int]),
    )

  given Schema[KekId] =
    Schema[String].transformOrFail(
      value =>
        if KekIdPattern.matcher(value).matches() then Right(value.asInstanceOf[KekId])
        else Left("KEK identifier must match [A-Za-z0-9:_-]{4,128}"),
      refined => Right(refined.asInstanceOf[String]),
    )

  given Schema[NonceLength] =
    Schema[Int].transformOrFail(
      value =>
        if value <= 0 || value > 32 then Left(s"Nonce length must be between 1 and 32, got $value")
        else Right(value.asInstanceOf[NonceLength]),
      refined => Right(refined.asInstanceOf[Int]),
    )
