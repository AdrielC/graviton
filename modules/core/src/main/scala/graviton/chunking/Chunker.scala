package graviton.chunking

import graviton.core.model.Block
import zio.stream.*
import zio.*

import AnchoredCdcPipeline.*
import graviton.GravitonError
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.{zio as _, *}

/** Splits a byte stream into logical chunks. */
trait Chunker:
  def name: String
  def pipeline: ZPipeline[Any, GravitonError, Byte, Block]

object Chunker:

  type ValidBounds[Min <: Int, Avg <: Int, Max <: Int] = (
    Constraint[Min, Greater[0]],
    Constraint[Avg, Greater[Min]],
    Constraint[Max, GreaterEqual[Avg]],
  )

  /** Chunk size bounds used by algorithms such as FastCDC. */
  final case class Bounds[Min <: Int, Avg <: Min, Max <: Avg](
    min: Min :| Greater[0],
    avg: Avg :| GreaterEqual[Min],
    max: Max :| GreaterEqual[Avg],
  )
  object Bounds:

    // @scala.annotation.publicInBinary
    // inline def apply[Min <: Int, Avg <: Int, Max <: Int](
    //   using Min: RuntimeConstraint[Min, Greater[0]], Avg: RuntimeConstraint[Avg, GreaterEqual[Min]], Max: RuntimeConstraint[Max, GreaterEqual[Avg]]
    // ): Bounds[Min, Avg, Max] =
    //   Bounds(
    //     compiletime.constValue[Min].refineUnsafe[Greater[0]],
    //     compiletime.constValue[Avg].refineUnsafe[GreaterEqual[Min]],
    //     compiletime.constValue[Max].refineUnsafe[GreaterEqual[Avg]]
    //   )
  end Bounds

  /** Strategy tag for telemetry & selection. */
  enum Strategy:
    case Fixed(size: Int)
    case FastCDC(bounds: Bounds[Int, Int, Int], normalization: Int = 2, window: Int = 64)
    case Rolling(bounds: Bounds[Int, Int, Int], window: Int = 48)
    case TokenAware(tokens: Set[String] :| Length[Greater[0]], maxChunkSize: Int :| Greater[0])
    case AnchoredCdc(
      pack: AnchoredCdcPipeline.TokenPack,
      avgSize: Int :| Greater[0],
      anchorBonus: Int :| Greater[1],
    )
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
    smallFileFixed: Int = 1 << 18,
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
    case Strategy.Fixed(sz)                               => FixedChunker(sz)
    case Strategy.FastCDC(b, n, w)                        =>
      FastCDCChunker(FastCDCChunker.Config(b, n, w))
    case Strategy.Rolling(b, w)                           =>
      RollingHashChunker(RollingHashChunker.Config(b, w))
    case Strategy.TokenAware(tokens, maxSz)               =>
      new Chunker:
        val name     = s"token-aware(max=$maxSz)"
        val pipeline = TokenAwareChunker
          .pipeline(tokens, maxSz)
          .mapError(e => GravitonError.ChunkerFailure(e.getMessage))
    case Strategy.AnchoredCdc(pack, avgSize, anchorBonus) =>
      new Chunker:
        val name     = s"anchored-cdc(${pack.name},avg=$avgSize,bonus=$anchorBonus)"
        val pipeline = ZPipeline
          .anchoredCdc(pack, avgSize, anchorBonus)
          .mapError(e => GravitonError.ChunkerFailure(e.getMessage))
    case Strategy.Pdf                                     => PdfChunker
    case Strategy.Smart(default, overrides)               =>
      // if Smart is provided directly, fall back to default without hints
      select(
        SmartConfig(default, overrides),
        new SmartHints:
          val contentType: Option[String] = None
          val contentLength: Option[Long] = None,
      )
  end fromStrategy

  inline val _min                          = 1024
  inline def _avg: Int :| Greater[1024]    = 1048576
  inline def _max: Int :| Greater[1048576] = 1073741824

  inline def default: ULayer[Chunker] = ZLayer.succeed(
    fromStrategy(
      Strategy.Smart(
        Strategy.Rolling(
          Bounds(_min, _avg, _max)
        ),
        List.empty,
      )
    )
  )
end Chunker
