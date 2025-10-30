package graviton.core.scan

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

/**
 * Refined opaque types for scan domain (simplified to avoid Iron ambiguities).
 */

opaque type ByteSize = Long

object ByteSize:
  def apply(n: Long): Either[String, ByteSize] =
    if n > 0 then Right(n) else Left(s"ByteSize must be positive: $n")
  def unsafe(n: Long): ByteSize = n
  extension (size: ByteSize)
    def toLong: Long = size
  val OneKiB: ByteSize = unsafe(1024L)
  val OneMiB: ByteSize = unsafe(1024L * 1024L)

opaque type Offset = Long

object Offset:
  def apply(n: Long): Either[String, Offset] =
    if n >= 0 then Right(n) else Left(s"Offset must be non-negative: $n")
  def unsafe(n: Long): Offset = n
  val Zero: Offset = unsafe(0L)
  extension (offset: Offset)
    def toLong: Long = offset

opaque type AlgoName = String

object AlgoName:
  def apply(s: String): AlgoName = s
  extension (name: AlgoName)
    def value: String = name
  val Blake3: AlgoName = apply("blake3")
  val SHA256: AlgoName = apply("sha256")

opaque type HexDigest = String

object HexDigest:
  def apply(s: String): HexDigest = s
  def fromBytes(bytes: Array[Byte]): HexDigest = 
    apply(bytes.map("%02x".format(_)).mkString)
  extension (digest: HexDigest)
    def value: String = digest

opaque type ChunkIndex = Long

object ChunkIndex:
  def apply(n: Long): ChunkIndex = n
  val Zero: ChunkIndex = apply(0L)
  extension (index: ChunkIndex)
    def toLong: Long = index

opaque type WindowSize = Int

object WindowSize:
  def apply(n: Int): Either[String, WindowSize] =
    if n >= 16 && n <= 128 then Right(n) 
    else Left(s"WindowSize must be 16-128: $n")
  def unsafe(n: Int): WindowSize = n
  extension (size: WindowSize)
    def toInt: Int = size
  val W32: WindowSize = unsafe(32)
  val W48: WindowSize = unsafe(48)
  val W64: WindowSize = unsafe(64)
