package graviton.streams

import graviton.core.model.{Block, ByteConstraints}
import graviton.core.scan.FS
import graviton.core.scan.FS.FastCDCParameters
import zio.stream.ZPipeline

object FastCDC:

  final case class Config(
    minBytes: Int,
    avgBytes: Int,
    maxBytes: Int,
    normalization: NormalizationLevel = NormalizationLevel.Level2,
  )

  object Config:
    val Default: Config = Config(
      minBytes = 256 * 1024,
      avgBytes = 1024 * 1024,
      maxBytes = 4 * 1024 * 1024,
    )

    def sanitize(config: Config): Config =
      val maxClamped = math.min(math.max(config.maxBytes, 1), ByteConstraints.MaxBlockBytes)
      val avgClamped = math.min(math.max(config.avgBytes, config.minBytes), maxClamped)
      val minClamped = math.min(math.max(config.minBytes, 1), avgClamped)
      config.copy(minBytes = minClamped, avgBytes = avgClamped, maxBytes = maxClamped)

  enum NormalizationLevel(val growBefore: Int, val shrinkAfter: Int):
    case Level0 extends NormalizationLevel(0, 0)
    case Level1 extends NormalizationLevel(1, 1)
    case Level2 extends NormalizationLevel(2, 2)
    case Level3 extends NormalizationLevel(3, 3)

  def chunker(config: Config = Config.Default): ZPipeline[Any, Throwable, Byte, Block] =
    val normalized = Config.sanitize(config)
    val params     = toParameters(normalized)
    val scan       = FS.fastCDCChunker(params)
    Chunker.pipelineFromScan(scan)

  private def toParameters(config: Config): FastCDCParameters =
    val baseMask  = maskFromSize(config.avgBytes)
    val smallMask = enlargeMask(baseMask, config.normalization.growBefore)
    val largeMask = shrinkMask(baseMask, config.normalization.shrinkAfter)
    FastCDCParameters(
      minBytes = config.minBytes,
      avgBytes = config.avgBytes,
      maxBytes = config.maxBytes,
      smallMask = math.max(1, smallMask),
      largeMask = math.max(1, largeMask),
    )

  private def maskFromSize(size: Int): Int =
    val safe = math.max(1, size)
    val bits =
      val candidate = safe - 1
      if candidate <= 0 then 1 else 32 - Integer.numberOfLeadingZeros(candidate)
    if bits <= 0 then 1 else (1 << math.min(bits, 30)) - 1

  private def enlargeMask(mask: Int, steps: Int): Int =
    var result = mask
    var idx    = 0
    while idx < steps do
      result = (result << 1) | 1
      idx += 1
    result

  private def shrinkMask(mask: Int, steps: Int): Int =
    if steps <= 0 then mask
    else
      val shrunk = mask >>> math.min(steps, 24)
      if shrunk == 0 then 1 else shrunk
