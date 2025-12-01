package graviton.chunking

import graviton.core.model.Block
import zio.stream.*

import AnchoredCdcPipeline.*
import graviton.GravitonError
import io.github.iltotore.iron.constraint.all.{Greater, GreaterEqual, Length}
import io.github.iltotore.iron.{zio as _, *}

import GravitonError.ChunkerFailure

type _ChunkingPipeline = ZPipeline[Any, ChunkerFailure, Byte, Block]

opaque type ChunkingPipeline <: _ChunkingPipeline = _ChunkingPipeline

object ChunkingPipeline:

  private[graviton] def apply(p: _ChunkingPipeline): ChunkingPipeline =
    p

  inline def anchoredCdc(
    tokenPack: TokenPack,
    avgSize: Int :| Greater[0],
    anchorBonus: Int :| Greater[0],
  ): ChunkingPipeline = AnchoredCdcPipeline.anchoredCdc(
    tokenPack = tokenPack,
    avgSize = avgSize,
    anchorBonus = anchorBonus,
  )

/** Splits a byte stream into logical chunks. */
trait Chunker:
  def name: String
  def pipeline: ChunkingPipeline

object Chunker:

  def fastCdc[Min <: Int, Avg <: Int, Max <: Int](
    bounds: Bounds[Min, Avg, Max],
    normalization: Int = 2,
    window: Int = 64,
  ): Chunker = FastCDCChunker(
    FastCDCChunker.Config(bounds, normalization, window)
  )

  inline def apply[Min <: Int, Avg <: Int, Max <: Int](
    config: FastCDCChunker.Config[Min, Avg, Max]
  ): Chunker = FastCDCChunker(
    config
  )

  type ValidBounds[Min <: Int, Avg <: Int, Max <: Int] = (
    Constraint[Int, Greater[0]],
    Constraint[Int, GreaterEqual[Min]],
    Constraint[Int, GreaterEqual[Avg]],
  )

  /** Chunk size bounds used by algorithms such as FastCDC. */

  final case class Bounds[Min <: Int, Avg <: Int, Max <: Int](
    min: (Min :| Greater[0]),
    avg: (Avg :| Greater[Min]),
    max: (Max :| Greater[Avg]),
  )
  object Bounds:

    @scala.annotation.publicInBinary
    transparent inline def mk[Min <: Int, Avg <: Int, Max <: Int](
      min: Min :| Greater[0]
    )(avg: Avg :| Greater[Min])(
      max: Max :| Greater[Avg]
    ): Bounds[Min, Avg, Max] =
      Bounds[Min, Avg, Max](
        min.assume[Greater[0]],
        avg.assume[Greater[Min]],
        max.assume[Greater[Avg]],
      )

    @scala.annotation.publicInBinary
    transparent inline given [Min <: Int, Avg <: Int, Max <: Int] => Bounds[Min, Avg, Max] =
      Bounds(
        compiletime.constValue[Min].assume[Greater[0]],
        compiletime.constValue[Avg].assume[Greater[Min]],
        compiletime.constValue[Max].assume[Greater[Avg]],
      )

    transparent inline given default: Bounds[1024, 1048576, 1073741824] =
      mk(
        1024.assume[Greater[0]]
      )(
        1048576.assume[Greater[1024]]
      )(
        1073741824.assume[Greater[1048576]]
      )

  end Bounds

  /** Strategy tag for telemetry & selection. */
  enum Strategy:
    case Fixed(size: Int)
    case FastCDC[Min <: Int, Avg <: Int, Max <: Int](bounds: Bounds[Min, Avg, Max], normalization: Int = 2, window: Int = 64)
    case Rolling[Min <: Int, Avg <: Int, Max <: Int](bounds: Bounds[Min, Avg, Max], window: Int = 48)
    case TokenAware(tokens: Set[String] :| Length[Greater[0]], maxChunkSize: Int :| Greater[0])
    case AnchoredCdc(
      pack: AnchoredCdcPipeline.TokenPack,
      avgSize: Int :| Greater[0],
      anchorBonus: Int :| Greater[0],
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
        val pipeline = TokenAwareChunker.pipeline(tokens, maxSz)
    case Strategy.AnchoredCdc(pack, avgSize, anchorBonus) =>
      new Chunker:
        val name     = s"anchored-cdc(${pack.name},avg=$avgSize,bonus=$anchorBonus)"
        val pipeline = AnchoredCdcPipeline.anchoredCdc(pack, avgSize, anchorBonus)
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

  final type Min = 1024
  final type Avg = 1048576
  final type Max = 1073741824

  val _min: (Min :| Greater[0])   = valueOf[Min].assume[Greater[0]]
  val _avg: (Avg :| Greater[Min]) = valueOf[Avg].assume[Greater[Min]]
  val _max: (Max :| Greater[Avg]) = valueOf[Max].assume[Greater[Avg]]

  def bounds = Bounds[_min.type, _avg.type, _max.type](_min, _avg, _max)

  def rolling(overrides: List[SmartRule] = Nil): Chunker =
    fromStrategy(
      Strategy.Smart(
        Strategy.Rolling(bounds),
        overrides,
      )
    )

end Chunker
