package graviton.core

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric

object types:
  type Algo          = String :| Match["(sha-256|blake3|md5)"]
  type HexLower      = String :| (Match["[0-9a-f]+"] & MinLength[2])
  type Mime          = String :| Match["[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+(;.*)?"]
  type Size          = Long :| numeric.Greater[-1]
  type ChunkSize     = Int :| numeric.Greater[0]
  type ChunkIndex    = Long :| numeric.Greater[-1]
  type ChunkCount    = Long :| numeric.Greater[-1]
  type LocatorScheme = String :| Match["[a-z0-9+.-]+"]
  type PathSegment   = String :| (Match["[^/]+"] & MinLength[1])
  type FileSegment   = String :| (Match["[^/]+"] & MinLength[1])

  private val Sha256HexLength = 64
  private val Md5HexLength    = 32

  def validateDigest(algo: Algo, hex: HexLower): Either[String, Unit] =
    algo match
      case "sha-256" =>
        Either.cond(hex.length == Sha256HexLength, (), s"sha-256 requires $Sha256HexLength hex chars, got ${hex.length}")
      case "md5"     =>
        Either.cond(hex.length == Md5HexLength, (), s"md5 requires $Md5HexLength hex chars, got ${hex.length}")
      case "blake3"  => Right(())
      case other     => Left(s"Unknown digest algorithm: $other")
