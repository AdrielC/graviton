package graviton.shared.cas

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.*

import scala.concurrent.{ExecutionContext, Future}

/**
 * Bounded, cross-platform content-addressing operations.
 *
 * This API is intentionally for control-plane and interactive payloads. The
 * server's data plane hashes arbitrary-size inputs incrementally instead of
 * collecting them into this bounded value.
 */
object ContentAddressing:
  final val MaxInteractiveBytes    = 8192
  final val MaxTextCodeUnits       = 2048
  final val MinimumFixedBlockBytes = 16
  final val MaximumFixedBlockBytes = 128

  /** Bytes that are safe to materialize for an interactive calculation. */
  type InteractiveBytes = Chunk[Byte] :| MaxLength[8192]

  object InteractiveBytes:
    val empty: InteractiveBytes = Chunk.empty[Byte].refineUnsafe[MaxLength[8192]]

    def fromChunk(bytes: Chunk[Byte]): Either[ContentAddressingError, InteractiveBytes] =
      bytes
        .refineEither[MaxLength[8192]]
        .left
        .map(_ => ContentAddressingError.PayloadTooLarge(bytes.length, MaxInteractiveBytes))

  /** A lowercase SHA-256 digest encoded as exactly 64 hexadecimal characters. */
  type Sha256Hex = Sha256Hex.T

  object Sha256Hex extends RefinedSubtype[String, Match["[0-9a-f]{64}"]]

  /** Byte count proven to fit inside an [[InteractiveBytes]] value. */
  type InteractiveByteCount = InteractiveByteCount.T

  object InteractiveByteCount extends RefinedSubtype[Long, GreaterEqual[0L] & LessEqual[8192L]]

  /** Fixed block size accepted by the browser content lab. */
  opaque type FixedBlockSize = Int

  object FixedBlockSize:
    def fromInt(value: Int): Either[ContentAddressingError, FixedBlockSize] =
      Either.cond(
        value >= MinimumFixedBlockBytes &&
          value <= MaximumFixedBlockBytes &&
          value % MinimumFixedBlockBytes == 0,
        value,
        ContentAddressingError.InvalidBlockSize(
          value,
          MinimumFixedBlockBytes,
          MaximumFixedBlockBytes,
        ),
      )

    extension (blockSize: FixedBlockSize) def value: Int = blockSize

  final case class ContentId(digest: Sha256Hex, size: InteractiveByteCount):
    def render: String = ContentKeyText.render("sha-256", digest.value, size.value)

  final case class Block(
    index: Int,
    offset: InteractiveByteCount,
    size: InteractiveByteCount,
    contentId: ContentId,
    duplicate: Boolean,
  )

  final case class Analysis(
    byteCount: InteractiveByteCount,
    blobId: ContentId,
    blocks: Chunk[Block],
    uniqueCount: Int,
    duplicateCount: Int,
  )

  def analyze(
    bytes: InteractiveBytes,
    requestedBlockSize: Int,
  ): IO[ContentAddressingError, Analysis] =
    ZIO
      .fromFuture(_ => analyzeFuture(bytes, requestedBlockSize))
      .mapError {
        case error: ContentAddressingError => error
        case _                             => ContentAddressingError.CryptoFailure("Platform SHA-256")
      }

  /**
   * Future facade used by Scala.js exports so the browser bundle does not need
   * to link the complete ZIO runtime. ZIO consumers should use [[analyze]].
   */
  def analyzeFuture(
    bytes: InteractiveBytes,
    requestedBlockSize: Int,
  ): Future[Analysis] =
    given ExecutionContext = ExecutionContext.parasitic

    for
      blockSize <- fromEither(FixedBlockSize.fromInt(requestedBlockSize))
      byteCount <- fromEither(interactiveCount(bytes.length.toLong))
      blobHash  <- PlatformSha256.digest(bytes)
      slices     = bytes.grouped(blockSize).toVector
      blocks    <- slices.zipWithIndex.foldLeft(
                     Future.successful(Vector.empty[(Int, InteractiveByteCount, InteractiveByteCount, ContentId)])
                   ) { case (accumulated, (slice, index)) =>
                     accumulated.flatMap { result =>
                       for
                         boundedSlice <- fromEither(InteractiveBytes.fromChunk(slice))
                         digest       <- PlatformSha256.digest(boundedSlice)
                         offset       <- fromEither(interactiveCount(index.toLong * blockSize.toLong))
                         size         <- fromEither(interactiveCount(slice.length.toLong))
                       yield result :+ (index, offset, size, ContentId(digest, size))
                     }
                   }
      analyzed   = markDuplicates(blocks)
      duplicates = analyzed.count(_.duplicate)
    yield Analysis(
      byteCount = byteCount,
      blobId = ContentId(blobHash, byteCount),
      blocks = Chunk.fromIterable(analyzed),
      uniqueCount = analyzed.length - duplicates,
      duplicateCount = duplicates,
    )

  private def interactiveCount(
    value: Long
  ): Either[ContentAddressingError, InteractiveByteCount] =
    InteractiveByteCount
      .either(value)
      .left
      .map(_ => ContentAddressingError.InvalidByteCount(value, MaxInteractiveBytes))

  private def fromEither[A](value: Either[ContentAddressingError, A]): Future[A] =
    value.fold(Future.failed, Future.successful)

  private def markDuplicates(
    blocks: Vector[(Int, InteractiveByteCount, InteractiveByteCount, ContentId)]
  ): Vector[Block] =
    blocks
      .foldLeft((Set.empty[String], Vector.empty[Block])) { case ((seen, result), (index, offset, size, contentId)) =>
        val rendered  = contentId.render
        val duplicate = seen.contains(rendered)
        (
          seen + rendered,
          result :+ Block(index, offset, size, contentId, duplicate),
        )
      }
      ._2

sealed abstract class ContentAddressingError(message: String) extends Exception(message)

object ContentAddressingError:
  final case class PayloadTooLarge(actualBytes: Int, maximumBytes: Int)
      extends ContentAddressingError(
        s"Payload is $actualBytes bytes; the interactive limit is $maximumBytes bytes."
      )

  final case class TextTooLarge(actualCodeUnits: Int, maximumCodeUnits: Int)
      extends ContentAddressingError(
        s"Text is $actualCodeUnits UTF-16 code units; the interactive limit is $maximumCodeUnits."
      )

  final case class InvalidBlockSize(actualBytes: Int, minimumBytes: Int, maximumBytes: Int)
      extends ContentAddressingError(
        s"Block size must be a multiple of $minimumBytes from $minimumBytes through $maximumBytes bytes; received $actualBytes."
      )

  final case class InvalidByteCount(actualBytes: Long, maximumBytes: Int)
      extends ContentAddressingError(
        s"Byte count $actualBytes is outside the interactive range 0 through $maximumBytes."
      )

  final case class InvalidDigest() extends ContentAddressingError("The platform returned an invalid SHA-256 digest.")

  final case class CryptoUnavailable() extends ContentAddressingError("This browser does not expose the Web Crypto SHA-256 API.")

  final case class TextEncodingUnavailable() extends ContentAddressingError("This browser does not expose the UTF-8 TextEncoder API.")

  final case class CryptoFailure(platform: String) extends ContentAddressingError(s"$platform could not compute SHA-256.")

/** Canonical wire format shared by the browser lab and server-side key types. */
object ContentKeyText:
  final case class Parts(algorithm: String, digestHex: String, size: Long)

  def render(algorithm: String, digestHex: String, size: Long): String =
    s"$algorithm:$digestHex:$size"

  def parse(value: String): Either[String, Parts] =
    value.split(":", -1).toList match
      case algorithm :: digestHex :: sizeText :: Nil =>
        for
          _    <- Either.cond(algorithm.nonEmpty, (), "Content key algorithm must not be empty")
          _    <- Either.cond(digestHex.nonEmpty, (), "Content key digest must not be empty")
          size <- scala.util.Try(sizeText.toLong).toEither.left.map(_ => s"Invalid byte length '$sizeText'")
          _    <- Either.cond(size >= 0, (), "Content key byte length must be non-negative")
        yield Parts(algorithm, digestHex, size)
      case _                                         => Left("Expected a content key in the form <algorithm>:<hex-digest>:<byte-length>")
