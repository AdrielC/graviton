package graviton.pdf

import graviton.core.model.Block
import graviton.core.types.UploadChunkSize
import graviton.streams.{Chunker, ChunkerCore}
import zio.{Chunk, ChunkBuilder, ZIO}
import zio.pdf.PdfObjectScanner
import zio.stream.{ZChannel, ZPipeline}

/**
 * A bounded-memory PDF chunker backed by zio-pdf's incremental object scanner.
 *
 * The chunker cuts at the first complete indirect-object boundary at or after
 * `targetBytes`. A single unusually large object is cut at `maxBytes`, so the
 * parser never turns a large upload into a large allocation. If zio-pdf cannot
 * safely resolve a structural boundary, the default policy continues with
 * bounded fixed-size cuts rather than rejecting an otherwise storable blob.
 */
object PdfAwareChunker:

  enum UnsupportedStructurePolicy derives CanEqual:
    case Reject
    case FixedSizeFallback

  sealed trait ConfigError extends Product with Serializable:
    def message: String

  object ConfigError:
    final case class TargetExceedsMaximum(targetBytes: Int, maxBytes: Int) extends ConfigError:
      val message: String = s"PDF target block size $targetBytes exceeds maximum block size $maxBytes"

  final case class Config private (
    targetBytes: UploadChunkSize,
    maxBytes: UploadChunkSize,
    maxCarryBytes: UploadChunkSize,
    unsupportedStructure: UnsupportedStructurePolicy,
  ):
    /**
     * Peak working bytes while emitting one block: parser carry, the mutable
     * in-flight block, one immutable emitted block, and the PDF signature.
     * Upstream chunks and downstream queues are outside this component.
     */
    def maximumOwnedBytes: Long =
      (2L * maxBytes.value.toLong) + maxCarryBytes.value.toLong + Signature.length.toLong

  object Config:
    val default: Config =
      Config(
        targetBytes = UploadChunkSize(1024 * 1024),
        maxBytes = UploadChunkSize(4 * 1024 * 1024),
        maxCarryBytes = UploadChunkSize(1024 * 1024),
        unsupportedStructure = UnsupportedStructurePolicy.FixedSizeFallback,
      )

    def make(
      targetBytes: UploadChunkSize,
      maxBytes: UploadChunkSize,
      maxCarryBytes: UploadChunkSize,
      unsupportedStructure: UnsupportedStructurePolicy = UnsupportedStructurePolicy.FixedSizeFallback,
    ): Either[ConfigError, Config] =
      Either.cond(
        targetBytes.value <= maxBytes.value,
        Config(targetBytes, maxBytes, maxCarryBytes, unsupportedStructure),
        ConfigError.TargetExceedsMaximum(targetBytes.value, maxBytes.value),
      )

  private val Signature: Chunk[Byte] =
    Chunk('%'.toByte, 'P'.toByte, 'D'.toByte, 'F'.toByte, '-'.toByte)

  def apply(config: Config = Config.default): Chunker =
    new Chunker:
      override val name: String =
        s"pdf-object-${config.targetBytes.value}-${config.maxBytes.value}"

      override val maximumBlockBytes: Int = config.maxBytes.value

      override val pipeline: ZPipeline[Any, Chunker.Err, Byte, Block] =
        PdfAwareChunker.pipeline(config)

  private def pipeline(config: Config): ZPipeline[Any, Chunker.Err, Byte, Block] =
    ZPipeline.fromChannel {
      def loop(state: State): ZChannel[Any, Chunker.Err, Chunk[Byte], Any, Chunker.Err, Chunk[Block], Any] =
        ZChannel.readWith(
          input =>
            ZChannel
              .fromZIO(ZIO.fromEither(state.feed(input)))
              .flatMap(blocks => write(blocks) *> loop(state)),
          error => ZChannel.fail(error),
          _ => ZChannel.fromZIO(ZIO.fromEither(state.finish())).flatMap(write),
        )

      loop(new State(config))
    }

  private def write(
    blocks: Chunk[Block]
  ): ZChannel[Any, Chunker.Err, Any, Any, Chunker.Err, Chunk[Block], Unit] =
    if blocks.isEmpty then ZChannel.unit else ZChannel.write(blocks)

  private final class State(config: Config):
    private val signature       = Array.ofDim[Byte](Signature.length)
    private val blockBuffer     = Array.ofDim[Byte](config.maxBytes.value)
    private val scannerConfig   = PdfObjectScanner.Config(config.maxCarryBytes.value)
    private var signatureLength = 0
    private var signatureValid  = false
    private var blockLength     = 0
    private var absoluteOffset  = 0L
    private var scanner         = Option(PdfObjectScanner.initial)
    private var fixedFallback   = false

    def feed(input: Chunk[Byte]): Either[Chunker.Err, Chunk[Block]] =
      if signatureValid then scanAndBuffer(input)
      else
        var consumed = 0
        while consumed < input.length && signatureLength < signature.length do
          signature(signatureLength) = input(consumed)
          signatureLength += 1
          consumed += 1

        if signatureLength < signature.length then Right(Chunk.empty)
        else if !signatureMatches then Left(notPdf)
        else
          signatureValid = true
          val prefix = Chunk.fromArray(signature.clone())
          for
            prefixBlocks <- scanAndBuffer(prefix)
            tailBlocks   <- scanAndBuffer(input.drop(consumed))
          yield prefixBlocks ++ tailBlocks

    def finish(): Either[Chunker.Err, Chunk[Block]] =
      if !signatureValid then Left(notPdf)
      else
        val validation = scanner match
          case None         => Right(())
          case Some(cursor) =>
            PdfObjectScanner.finish(cursor) match
              case Right(_)    => Right(())
              case Left(error) => handleScannerError(error)

        validation.flatMap(_ => emitRemainder())

    private def scanAndBuffer(input: Chunk[Byte]): Either[Chunker.Err, Chunk[Block]] =
      if input.isEmpty then Right(Chunk.empty)
      else
        val boundaries = scanner match
          case None         => Right(Chunk.empty)
          case Some(cursor) =>
            PdfObjectScanner.step(scannerConfig, cursor, input) match
              case Right((next, found)) =>
                scanner = Some(next)
                Right(found)
              case Left(error)          =>
                handleScannerError(error).map(_ => Chunk.empty)

        boundaries.flatMap(buffer(input, _))

    private def handleScannerError(error: PdfObjectScanner.Error): Either[Chunker.Err, Unit] =
      config.unsupportedStructure match
        case UnsupportedStructurePolicy.FixedSizeFallback =>
          scanner = None
          fixedFallback = true
          Right(())
        case UnsupportedStructurePolicy.Reject            =>
          Left(
            ChunkerCore.Err.FormatViolation(
              format = "pdf",
              message = error.message,
            )
          )

    private def buffer(
      input: Chunk[Byte],
      boundaries: Chunk[PdfObjectScanner.Boundary],
    ): Either[Chunker.Err, Chunk[Block]] =
      val output        = ChunkBuilder.make[Block]()
      var boundaryIndex = 0
      var inputIndex    = 0

      while inputIndex < input.length do
        blockBuffer(blockLength) = input(inputIndex)
        blockLength += 1
        absoluteOffset += 1L

        while boundaryIndex < boundaries.length && boundaries(boundaryIndex).nextByteOffset < absoluteOffset do boundaryIndex += 1

        val atObjectBoundary =
          boundaryIndex < boundaries.length && boundaries(boundaryIndex).nextByteOffset == absoluteOffset
        val cutAtBoundary    = atObjectBoundary && blockLength >= config.targetBytes.value
        val cutAtFallback    = fixedFallback && blockLength >= config.targetBytes.value
        val cutAtMaximum     = blockLength >= config.maxBytes.value

        if cutAtBoundary || cutAtFallback || cutAtMaximum then
          emitBlock() match
            case Left(error)  => return Left(error)
            case Right(block) => output += block

        if atObjectBoundary then boundaryIndex += 1
        inputIndex += 1

      Right(output.result())

    private def emitRemainder(): Either[Chunker.Err, Chunk[Block]] =
      if blockLength == 0 then Right(Chunk.empty)
      else emitBlock().map(Chunk.single)

    private def emitBlock(): Either[Chunker.Err, Block] =
      val bytes = Chunk.fromArray(java.util.Arrays.copyOf(blockBuffer, blockLength))
      Block
        .fromChunk(bytes)
        .left
        .map(ChunkerCore.Err.InvalidBlock.apply)
        .map { block =>
          blockLength = 0
          block
        }

    private def signatureMatches: Boolean =
      var index = 0
      while index < signature.length && signature(index) == Signature(index) do index += 1
      index == signature.length

    private def notPdf: ChunkerCore.Err.FormatViolation =
      ChunkerCore.Err.FormatViolation(
        format = "pdf",
        message = "advertised application/pdf bytes do not start with %PDF-",
      )

end PdfAwareChunker
