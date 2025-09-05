package graviton.chunking

import zio.*
import zio.stream.*

/** Splits a byte stream into logical chunks. */
trait Chunker:
  def name: String
  def pipeline: ZPipeline[Any, Throwable, Byte, Chunk[Byte]]

object Chunker:

  /** Chunk size bounds used by algorithms such as FastCDC. */
  final case class Bounds(min: Int, avg: Int, max: Int):
    require(
      min > 0 && avg >= min && max >= avg,
      "Bounds(min <= avg <= max) violated"
    )

  /** Strategy tag for telemetry & selection. */
  enum Strategy:
    case Fixed(size: Int)
    case FastCDC(bounds: Bounds, normalization: Int = 2, window: Int = 64)
    case Rolling(bounds: Bounds, window: Int = 48)
    case TokenAware(tokens: Set[String], maxChunkSize: Int)
    case Pdf
    case Smart(default: Strategy, overrides: List[SmartRule])

  /** Rules used by [[Strategy.Smart]] to select a chunker at runtime. */
  final case class SmartRule(whenContentTypeStartsWith: String, use: Strategy)

  /** Runtime hints when selecting a smart strategy. */
  trait SmartHints:
    def contentType: Option[String]
    def contentLength: Option[Long]

  /** Configuration for smart selection. */
  final case class SmartConfig(
      default: Strategy,
      rules: List[SmartRule],
      smallFileFixed: Int = 1 << 18
  )

  /** Select a [[Chunker]] based on the provided [[SmartConfig]] and hints. */
  def select(cfg: SmartConfig, hints: SmartHints): Chunker =
    val chosen =
      hints.contentType
        .flatMap(ct =>
          cfg.rules
            .find(r => ct.startsWith(r.whenContentTypeStartsWith))
            .map(_.use)
        )
        .orElse(hints.contentLength.collect {
          case n if n <= cfg.smallFileFixed => Strategy.Fixed(n.toInt.max(1))
        })
        .getOrElse(cfg.default)
    fromStrategy(chosen)

  /** Build a [[Chunker]] from a strategy. */
  def fromStrategy(s: Strategy): Chunker = s match
    case Strategy.Fixed(sz) => FixedChunker(sz)
    case Strategy.FastCDC(b, n, w) =>
      FastCDCChunker(FastCDCChunker.Config(b, n, w))
    case Strategy.Rolling(b, w) =>
      RollingHashChunker(RollingHashChunker.Config(b, w))
    case Strategy.TokenAware(tokens, maxSz) =>
      new Chunker:
        val name = s"token-aware(max=$maxSz)"
        val pipeline = TokenAwareChunker.pipeline(tokens, maxSz)
    case Strategy.Pdf                       => PdfChunker
    case Strategy.Smart(default, overrides) =>
      // if Smart is provided directly, fall back to default without hints
      select(
        SmartConfig(default, overrides),
        new SmartHints:
          val contentType: Option[String] = None
          val contentLength: Option[Long] = None
      )
