package graviton.contentlab

import graviton.shared.cas.ContentKeyText
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import zio.*
import zio.blocks.mediatype.{MediaType, MediaTypes}
import zio.pdf.cdc.FastCdc
import zio.pdf.{PdfMime, PdfObjectScanner, PdfSource}
import zio.stream.{ZChannel, ZPipeline, ZStream}

/**
 * Streaming browser analysis used by the CAS Playground.
 *
 * File bytes remain in the browser-owned Blob and are pulled through one
 * backpressured stream. Only one bounded block is materialized at a time.
 * Result metadata is capped separately so a tiny block profile cannot create
 * an unbounded DOM or Scala collection.
 */
object BrowserFileAnalysis:
  final val MinimumTargetBytes = 16 * 1024
  final val MaximumTargetBytes = 1024 * 1024
  final val MaximumBlockBytes  = 4 * 1024 * 1024
  final val MaximumBlocks      = 20000
  final val HashWindowBytes    = 64 * 1024

  type BlockBytes = Chunk[Byte] :| (MinLength[1] & MaxLength[4194304])

  object FileSize extends RefinedSubtype[Long, GreaterEqual[0L] & LessEqual[9007199254740991L]]
  type FileSize = FileSize.T

  enum Strategy(val wireName: String, val label: String) derives CanEqual:
    case Auto          extends Strategy("auto", "Automatic")
    case PdfStructural extends Strategy("pdf", "PDF structures")
    case FastCdc       extends Strategy("fastcdc", "FastCDC")
    case Fixed         extends Strategy("fixed", "Fixed ranges")

  object Strategy:
    def parse(value: String): Either[Error, Strategy] =
      Strategy.values
        .find(_.wireName == value)
        .toRight(Error.InvalidStrategy(value))

  enum Cut(val wireName: String) derives CanEqual:
    case PdfObject      extends Cut("pdf-object")
    case PdfFallback    extends Cut("pdf-fallback")
    case ContentDefined extends Cut("content-defined")
    case Fixed          extends Cut("fixed")
    case Maximum        extends Cut("maximum")
    case Remainder      extends Cut("remainder")

  final case class Config private (requested: Strategy, targetBytes: Int, maximumBytes: Int):
    val minimumBytes: Int       = math.max(MinimumTargetBytes, targetBytes / 4)
    val maximumOwnedBytes: Long =
      (2L * maximumBytes.toLong) +
        math.min(targetBytes, 1024 * 1024).toLong +
        BrowserBlobSource.ReadBytes.toLong +
        HashWindowBytes.toLong +
        PdfChunker.SignatureLength +
        4L // two broadcast subscribers with maximumLag = 2 bytes

  object Config:
    def make(requested: Strategy, targetBytes: Int): Either[Error, Config] =
      val powerOfTwo = targetBytes > 0 && (targetBytes & (targetBytes - 1)) == 0
      Either.cond(
        powerOfTwo && targetBytes >= MinimumTargetBytes && targetBytes <= MaximumTargetBytes,
        Config(requested, targetBytes, math.min(MaximumBlockBytes, Math.multiplyExact(targetBytes, 4))),
        Error.InvalidTargetSize(targetBytes, MinimumTargetBytes, MaximumTargetBytes),
      )

  final case class BlockRange(
    index: Int,
    start: Long,
    length: Int,
    digestHex: String,
    contentId: String,
    cut: Cut,
    duplicateWithinFile: Boolean,
  ):
    def endExclusive: Long = start + length.toLong

  final case class Analysis(
    byteCount: FileSize,
    contentId: String,
    digestHex: String,
    advertisedMediaType: MediaType,
    confirmedMediaType: MediaType,
    pdfSignature: Boolean,
    mediaTypeMismatch: Boolean,
    strategy: Strategy,
    targetBytes: Int,
    maximumBlockBytes: Int,
    maximumOwnedBytes: Long,
    blocks: Chunk[BlockRange],
  ):
    lazy val uniqueBlocks: Int    = blocks.count(!_.duplicateWithinFile)
    lazy val duplicateBlocks: Int = blocks.length - uniqueBlocks

  sealed abstract class Error(message: String) extends Exception(message)

  object Error:
    final case class InvalidStrategy(value: String)  extends Error(s"Unknown chunking strategy '$value'.")
    final case class InvalidTargetSize(actual: Int, minimum: Int, maximum: Int)
        extends Error(s"Target block size must be a power of two from $minimum through $maximum bytes; received $actual.")
    final case class InvalidFileSize(actual: Double) extends Error(s"The browser reported an invalid file size: $actual.")
    final case class InvalidMediaType(value: String, detail: String)
        extends Error(s"The browser supplied an invalid media type '$value': $detail")
    final case class ExpectedPdf()                   extends Error("PDF structural chunking requires bytes beginning with %PDF-.")
    final case class PdfStructure(detail: String)    extends Error(s"ZIO PDF could not scan this document structure: $detail")
    final case class BlockLimitExceeded(maximum: Int)
        extends Error(s"This profile produces more than $maximum blocks. Choose a larger target block size.")
    final case class FileChanged(expected: Long, observed: Long)
        extends Error(s"The file changed while it was being analyzed: expected $expected bytes, streamed $observed.")
    final case class Crypto(detail: String)          extends Error(s"SHA-256 failed: $detail")
    final case class Stream(detail: String)          extends Error(s"The browser could not stream this file: $detail")

  def fileSize(value: Double): Either[Error, FileSize] =
    if value.isNaN || value.isInfinity || value < 0.0 || value > 9007199254740991.0 || value != math.floor(value) then
      Left(Error.InvalidFileSize(value))
    else FileSize.either(value.toLong).left.map(_ => Error.InvalidFileSize(value))

  def mediaType(value: String): Either[Error, MediaType] =
    if value == null || value.trim.isEmpty then Right(MediaTypes.application.`octet-stream`)
    else MediaType.parse(value).left.map(detail => Error.InvalidMediaType(value, detail))

  def analyze(
    source: PdfSource,
    expectedSize: FileSize,
    advertisedMediaType: MediaType,
    config: Config,
    onBlock: BlockRange => UIO[Unit] = _ => ZIO.unit,
  ): IO[Error, Analysis] =
    for
      isPdf           <- sniffPdf(source)
      strategy        <- resolveStrategy(config.requested, isPdf)
      result          <- ZIO.scoped {
                           source.bytes.broadcast(2, maximumLag = 2).flatMap { branches =>
                             val digest = StreamingSha256
                               .digest(branches(0))
                               .mapError(error => Error.Crypto(message(error)))
                             val blocks = analyzeBlocks(branches(1), strategy, config, onBlock)
                             digest.zipPar(blocks)
                           }
                         }
      (digest, ranges) = result
      observed         = ranges.foldLeft(0L)((total, block) => total + block.length.toLong)
      _               <- ZIO.fail(Error.FileChanged(expectedSize.value, observed)).unless(observed == expectedSize.value)
      digestHex       <- ZIO.fromEither(hex(digest)).mapError(Error.Crypto.apply)
      advertisedPdf    = PdfMime.mimeType.matches(advertisedMediaType, ignoreParameters = true)
      confirmed        =
        if isPdf then PdfMime.mimeType
        else if advertisedPdf then MediaTypes.application.`octet-stream`
        else advertisedMediaType
    yield Analysis(
      byteCount = expectedSize,
      contentId = ContentKeyText.render("sha-256", digestHex, expectedSize.value),
      digestHex = digestHex,
      advertisedMediaType = advertisedMediaType,
      confirmedMediaType = confirmed,
      pdfSignature = isPdf,
      mediaTypeMismatch = isPdf != advertisedPdf,
      strategy = strategy,
      targetBytes = config.targetBytes,
      maximumBlockBytes = config.maximumBytes,
      maximumOwnedBytes = config.maximumOwnedBytes,
      blocks = markDuplicates(ranges),
    )

  private def resolveStrategy(requested: Strategy, isPdf: Boolean): IO[Error, Strategy] =
    requested match
      case Strategy.Auto                    => ZIO.succeed(if isPdf then Strategy.PdfStructural else Strategy.FastCdc)
      case Strategy.PdfStructural if !isPdf => ZIO.fail(Error.ExpectedPdf())
      case explicit                         => ZIO.succeed(explicit)

  private def sniffPdf(source: PdfSource): IO[Error, Boolean] =
    BoundedBrowserBytes
      .pdfSignature(source.bytes)
      .mapError(error => Error.Stream(message(error)))
      .map(PdfChunker.matchesSignature)

  private def analyzeBlocks(
    bytes: ZStream[Any, Throwable, Byte],
    strategy: Strategy,
    config: Config,
    onBlock: BlockRange => UIO[Unit],
  ): IO[Error, Chunk[BlockRange]] =
    val raw = strategy match
      case Strategy.PdfStructural =>
        bytes
          .via(PdfChunker.pipeline(config.targetBytes, config.maximumBytes, math.min(config.targetBytes, 1024 * 1024)))
          .mapError(error => Error.PdfStructure(error.getMessage))
      case Strategy.FastCdc       =>
        val cdc = FastCdc.Config(config.minimumBytes, config.targetBytes, config.maximumBytes)
        bytes
          .via(FastCdc.pipeline(cdc))
          .map(chunk => RawBlock(refineBlock(chunk), Cut.ContentDefined))
          .mapError(error => Error.Stream(message(error)))
      case Strategy.Fixed         =>
        bytes
          .rechunk(config.targetBytes)
          .chunks
          .filter(_.nonEmpty)
          .map(chunk => RawBlock(refineBlock(chunk), Cut.Fixed))
          .mapError(error => Error.Stream(message(error)))
      case Strategy.Auto          =>
        ZStream.fail(Error.InvalidStrategy("auto was not resolved"))

    val ranges = raw.zipWithIndex
      .mapAccumZIO(0L) { case (offset, (block, index)) =>
        StreamingSha256
          .digest(ZStream.fromChunk(block.bytes))
          .mapError(error => Error.Crypto(message(error)))
          .flatMap(digest => ZIO.fromEither(hex(digest)).mapError(Error.Crypto.apply))
          .map { digestHex =>
            val length = block.bytes.length
            val range  = BlockRange(
              index = index.toInt,
              start = offset,
              length = length,
              digestHex = digestHex,
              contentId = ContentKeyText.render("sha-256", digestHex, length.toLong),
              cut = block.cut,
              duplicateWithinFile = false,
            )
            (offset + length.toLong, range)
          }
      }
      .tap(onBlock)

    collectBlockMetadata(ranges, MaximumBlocks)

  private[contentlab] def collectBlockMetadata(
    ranges: ZStream[Any, Error, BlockRange],
    maximumBlocks: Int,
  ): IO[Error, Chunk[BlockRange]] =
    ranges
      .take(maximumBlocks.toLong + 1L)
      .runFold(Chunk.empty[BlockRange])(_ :+ _)
      .flatMap { blocks =>
        if blocks.length > maximumBlocks then ZIO.fail(Error.BlockLimitExceeded(maximumBlocks))
        else ZIO.succeed(blocks)
      }

  private def markDuplicates(blocks: Chunk[BlockRange]): Chunk[BlockRange] =
    blocks
      .foldLeft((Set.empty[String], Chunk.empty[BlockRange])) { case ((seen, result), block) =>
        val duplicate = seen.contains(block.contentId)
        (seen + block.contentId, result :+ block.copy(duplicateWithinFile = duplicate))
      }
      ._2

  private def refineBlock(bytes: Chunk[Byte]): BlockBytes =
    bytes.refineUnsafe[MinLength[1] & MaxLength[4194304]]

  private def hex(bytes: Chunk[Byte]): Either[String, String] =
    if bytes.length != 32 then Left(s"expected a 32-byte digest, received ${bytes.length}")
    else
      val digits  = "0123456789abcdef"
      val builder = new StringBuilder(64)
      bytes.foreach { byte =>
        val value = byte & 0xff
        builder.append(digits.charAt(value >>> 4))
        builder.append(digits.charAt(value & 0x0f))
      }
      Right(builder.result())

  private def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  private final case class RawBlock(bytes: BlockBytes, cut: Cut)

  private object PdfChunker:
    final val SignatureLength = 5
    private val Signature     = Chunk('%'.toByte, 'P'.toByte, 'D'.toByte, 'F'.toByte, '-'.toByte)

    def matchesSignature(bytes: Chunk[Byte]): Boolean = bytes == Signature

    def pipeline(targetBytes: Int, maximumBytes: Int, carryBytes: Int): ZPipeline[Any, Error, Byte, RawBlock] =
      ZPipeline.fromChannel {
        def loop(state: State): ZChannel[Any, Error, Chunk[Byte], Any, Error, Chunk[RawBlock], Any] =
          ZChannel.readWith(
            input => ZChannel.fromEither(state.feed(input)).flatMap(blocks => write(blocks) *> loop(state)),
            error => ZChannel.fail(error),
            _ => ZChannel.fromEither(state.finish()).flatMap(write),
          )

        loop(new State(targetBytes, maximumBytes, carryBytes))
      }

    private def write(blocks: Chunk[RawBlock]): ZChannel[Any, Error, Any, Any, Error, Chunk[RawBlock], Unit] =
      if blocks.isEmpty then ZChannel.unit else ZChannel.write(blocks)

    private final class State(targetBytes: Int, maximumBytes: Int, carryBytes: Int):
      private val signature       = Array.ofDim[Byte](SignatureLength)
      private val blockBuffer     = Array.ofDim[Byte](maximumBytes)
      private val scannerConfig   = PdfObjectScanner.Config(carryBytes)
      private var signatureLength = 0
      private var signatureValid  = false
      private var blockLength     = 0
      private var absoluteOffset  = 0L
      private var scanner         = Option(PdfObjectScanner.initial)
      private var fixedFallback   = false

      def feed(input: Chunk[Byte]): Either[Error, Chunk[RawBlock]] =
        if signatureValid then scanAndBuffer(input)
        else
          var consumed = 0
          while consumed < input.length && signatureLength < signature.length do
            signature(signatureLength) = input(consumed)
            signatureLength += 1
            consumed += 1

          if signatureLength < signature.length then Right(Chunk.empty)
          else if !matchesSignature(Chunk.fromArray(signature)) then Left(Error.ExpectedPdf())
          else
            signatureValid = true
            for
              prefix <- scanAndBuffer(Chunk.fromArray(signature.clone()))
              tail   <- scanAndBuffer(input.drop(consumed))
            yield prefix ++ tail

      def finish(): Either[Error, Chunk[RawBlock]] =
        if !signatureValid then Left(Error.ExpectedPdf())
        else
          scanner match
            case Some(cursor) => PdfObjectScanner.finish(cursor).left.foreach(_ => fixedFallback = true)
            case None         => ()
          emitRemainder()

      private def scanAndBuffer(input: Chunk[Byte]): Either[Error, Chunk[RawBlock]] =
        if input.isEmpty then Right(Chunk.empty)
        else
          val boundaries = scanner match
            case None         => Chunk.empty
            case Some(cursor) =>
              PdfObjectScanner.step(scannerConfig, cursor, input) match
                case Right((next, found)) =>
                  scanner = Some(next)
                  found
                case Left(_)              =>
                  scanner = None
                  fixedFallback = true
                  Chunk.empty
          buffer(input, boundaries)

      private def buffer(input: Chunk[Byte], boundaries: Chunk[PdfObjectScanner.Boundary]): Either[Error, Chunk[RawBlock]] =
        val output        = Chunk.newBuilder[RawBlock]
        var boundaryIndex = 0
        var inputIndex    = 0

        while inputIndex < input.length do
          blockBuffer(blockLength) = input(inputIndex)
          blockLength += 1
          absoluteOffset += 1L

          while boundaryIndex < boundaries.length && boundaries(boundaryIndex).nextByteOffset < absoluteOffset do boundaryIndex += 1

          val atObjectBoundary =
            boundaryIndex < boundaries.length && boundaries(boundaryIndex).nextByteOffset == absoluteOffset
          val cut              =
            if blockLength >= maximumBytes then Some(Cut.Maximum)
            else if fixedFallback && blockLength >= targetBytes then Some(Cut.PdfFallback)
            else if atObjectBoundary && blockLength >= targetBytes then Some(Cut.PdfObject)
            else None

          cut.foreach(reason => output += emitBlock(reason))
          if atObjectBoundary then boundaryIndex += 1
          inputIndex += 1

        Right(output.result())

      private def emitRemainder(): Either[Error, Chunk[RawBlock]] =
        if blockLength == 0 then Right(Chunk.empty)
        else Right(Chunk.single(emitBlock(Cut.Remainder)))

      private def emitBlock(reason: Cut): RawBlock =
        val bytes = Chunk.fromArray(java.util.Arrays.copyOf(blockBuffer, blockLength))
        blockLength = 0
        RawBlock(refineBlock(bytes), reason)

end BrowserFileAnalysis
