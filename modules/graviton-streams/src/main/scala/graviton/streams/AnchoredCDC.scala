package graviton.streams

import graviton.core.model.Block
import graviton.core.scan.FS
import graviton.core.scan.FS.{AnchorPattern => FsAnchorPattern, AnchoredCDCParameters}
import zio.Chunk
import zio.stream.ZPipeline

import java.nio.charset.StandardCharsets

object AnchoredCDC:

  final case class Anchor(
    label: String,
    bytes: Chunk[Byte],
    includePattern: Boolean = true,
    scanBudgetBytes: Option[Int] = None,
  )

  final case class Config(
    minBytes: Int,
    avgBytes: Int,
    maxBytes: Int,
    anchors: Chunk[Anchor],
    scanWindowBytes: Int = 256 * 1024,
    rechunkThreshold: Double = 0.9,
  )

  object Config:
    val Default: Config = Config(
      minBytes = 128 * 1024,
      avgBytes = 512 * 1024,
      maxBytes = 2 * 1024 * 1024,
      anchors = Chunk.empty,
    )

    def sanitize(config: Config): Config =
      val maxClamped = math.max(1, config.maxBytes)
      val avgClamped = math.min(math.max(config.avgBytes, config.minBytes), maxClamped)
      val minClamped = math.min(math.max(1, config.minBytes), avgClamped)
      val window     = math.max(1, config.scanWindowBytes)
      val threshold  = math.max(0.1, math.min(0.99, config.rechunkThreshold))
      config.copy(
        minBytes = minClamped,
        avgBytes = avgClamped,
        maxBytes = maxClamped,
        scanWindowBytes = window,
        rechunkThreshold = threshold,
      )

  def chunker(config: Config): ZPipeline[Any, Throwable, Byte, Block] =
    val normalized = Config.sanitize(config)
    val parameters = AnchoredCDCParameters(
      minBytes = normalized.minBytes,
      avgBytes = normalized.avgBytes,
      maxBytes = normalized.maxBytes,
      scanWindowBytes = normalized.scanWindowBytes,
      rechunkThreshold = normalized.rechunkThreshold,
      anchors = normalized.anchors.map(toFsAnchor),
    )
    Chunker.pipelineFromScan(FS.anchoredCDCChunker(parameters))

  object Pdf:
    private val ascii = StandardCharsets.US_ASCII

    private def literal(value: String): Chunk[Byte] =
      Chunk.fromArray(value.getBytes(ascii))

    val streamStart: Anchor = Anchor("pdf.stream", literal("stream\n"), includePattern = true, scanBudgetBytes = Some(32 * 1024))
    val streamEnd: Anchor   = Anchor("pdf.endstream", literal("endstream"), includePattern = true, scanBudgetBytes = Some(32 * 1024))
    val objectEnd: Anchor   = Anchor("pdf.endobj", literal("endobj"), includePattern = true, scanBudgetBytes = Some(64 * 1024))
    val xref: Anchor        = Anchor("pdf.xref", literal("\nxref"), includePattern = true, scanBudgetBytes = Some(64 * 1024))
    val trailer: Anchor     = Anchor("pdf.trailer", literal("trailer"), includePattern = true, scanBudgetBytes = Some(64 * 1024))
    val startxref: Anchor   = Anchor("pdf.startxref", literal("startxref"), includePattern = true, scanBudgetBytes = Some(64 * 1024))

    val semanticAnchors: Chunk[Anchor] =
      Chunk(streamEnd, objectEnd, streamStart, xref, trailer, startxref)

    val semanticConfig: Config =
      Config(
        minBytes = 64 * 1024,
        avgBytes = 512 * 1024,
        maxBytes = 2 * 1024 * 1024,
        anchors = semanticAnchors,
        scanWindowBytes = 512 * 1024,
        rechunkThreshold = 0.85,
      )

  private def toFsAnchor(anchor: Anchor): FsAnchorPattern =
    FsAnchorPattern(
      label = anchor.label,
      bytes = anchor.bytes,
      includePattern = anchor.includePattern,
      scanBudgetBytes = anchor.scanBudgetBytes,
    )
