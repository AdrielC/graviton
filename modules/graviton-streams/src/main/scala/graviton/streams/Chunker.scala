package graviton.streams

import graviton.core.model.Block
import graviton.core.types.*
import zio.*
import zio.stream.*

import scala.Conversion

trait Chunker:
  def name: String
  def pipeline: ZPipeline[Any, Throwable, Byte, Block]

object Chunker:
  private val DefaultChunkBytes = 1024 * 1024

  private val defaultChunkSize: UploadChunkSize =
    UploadChunkSize.either(DefaultChunkBytes).fold(_ => UploadChunkSize.unsafe(DefaultChunkBytes), identity)

  val default: Chunker = fixed(defaultChunkSize)

  val current: FiberRef[Chunker] =
    Unsafe.unsafe(implicit unsafe => FiberRef.unsafe.make(default))

  def locally[R, E, A](chunker: Chunker)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    current.locally(chunker)(effect)

  def fixed(size: UploadChunkSize, label: Option[String] = None): Chunker =
    val sizeBytes = size.value
    val pipeline  = Incremental.pipelineFixed(chunkBytes = sizeBytes)
    SimpleChunker(label.getOrElse(s"fixed-$sizeBytes"), pipeline)

  def fastCdc(
    min: Int,
    avg: Int,
    max: Int,
    label: Option[String] = None,
  ): Chunker =
    val pipeline = Incremental.pipelineFastCdc(minBytes = min, avgBytes = avg, maxBytes = max)
    SimpleChunker(label.getOrElse(s"fastcdc-$min-$avg-$max"), pipeline)

  def delimiter(
    delim: Chunk[Byte],
    includeDelimiter: Boolean = true,
    minBytes: Int = 1,
    maxBytes: Int = Block.maxBytes,
    label: Option[String] = None,
  ): Chunker =
    val pipeline =
      if delim.isEmpty then ZPipeline.fromChannel(ZChannel.fail(new IllegalArgumentException("Delimiter cannot be empty")))
      else
        Incremental.pipelineDelimiter(
          delim = delim,
          includeDelimiter = includeDelimiter,
          minBytes = minBytes,
          maxBytes = maxBytes,
        )
    val dLen     = delim.length
    SimpleChunker(label.getOrElse(s"delimiter-$dLen-${if includeDelimiter then "incl" else "excl"}"), pipeline)

  given chunkerToPipeline: Conversion[Chunker, ZPipeline[Any, Throwable, Byte, Block]] with
    def apply(chunker: Chunker): ZPipeline[Any, Throwable, Byte, Block] =
      chunker.pipeline

private final case class SimpleChunker(
  name: String,
  pipeline: ZPipeline[Any, Throwable, Byte, Block],
) extends Chunker

private object Incremental:

  private final case class Bounds(minBytes: Int, avgBytes: Int, maxBytes: Int)

  private sealed trait Detector
  private object Detector:
    case object Fixed                                                                extends Detector
    final case class Delimiter(delim: Array[Byte], pi: Array[Int], include: Boolean) extends Detector
    final case class FastCdc(strongMask: Int, normalMask: Int)                       extends Detector

  private final case class St(
    buf: Array[Byte],
    len: Int,
    detState: Int, // Fixed: count, Delimiter: matched prefix length, FastCDC: rolling hash
  )

  def pipelineFixed(chunkBytes: Int): ZPipeline[Any, Throwable, Byte, Block] =
    val size     = math.max(1, math.min(chunkBytes, Block.maxBytes))
    val bounds   = Bounds(minBytes = size, avgBytes = size, maxBytes = size)
    val detector = Detector.Fixed
    pipeline(bounds, detector, delimLen = 0)

  def pipelineDelimiter(
    delim: Chunk[Byte],
    includeDelimiter: Boolean,
    minBytes: Int,
    maxBytes: Int,
  ): ZPipeline[Any, Throwable, Byte, Block] =
    val delimArr = delim.toArray
    val dLen     = delimArr.length
    val safeMin  = math.max(1, minBytes)
    val safeMax  = math.max(safeMin, math.min(maxBytes, Block.maxBytes))
    val bounds   = Bounds(minBytes = safeMin, avgBytes = safeMin, maxBytes = safeMax)
    val detector = Detector.Delimiter(delimArr, kmpPrefix(delimArr), includeDelimiter)
    pipeline(bounds, detector, delimLen = dLen)

  def pipelineFastCdc(minBytes: Int, avgBytes: Int, maxBytes: Int): ZPipeline[Any, Throwable, Byte, Block] =
    val safeMin                  = math.max(1, minBytes)
    val safeMax                  = math.max(safeMin, math.min(maxBytes, Block.maxBytes))
    val safeAvg                  = math.max(safeMin, math.min(avgBytes, safeMax))
    val (strongMask, normalMask) = fastCdcMasks(safeAvg)
    val bounds                   = Bounds(minBytes = safeMin, avgBytes = safeAvg, maxBytes = safeMax)
    val detector                 = Detector.FastCdc(strongMask = strongMask, normalMask = normalMask)
    pipeline(bounds, detector, delimLen = 0)

  private def pipeline(
    bounds: Bounds,
    detector: Detector,
    delimLen: Int,
  ): ZPipeline[Any, Throwable, Byte, Block] =
    ZPipeline.fromChannel {
      def loop(st0: St): ZChannel[Any, Throwable, Chunk[Byte], Any, Throwable, Chunk[Block], Any] =
        ZChannel.readWith(
          (in: Chunk[Byte]) =>
            ZChannel
              .fromZIO {
                val out = ChunkBuilder.make[Block]()

                val buf      = st0.buf
                var len      = st0.len
                var detState = st0.detState
                var failure  = Option.empty[Throwable]

                var idx = 0
                while idx < in.length && failure.isEmpty do
                  val b = in(idx)

                  buf(len) = b
                  len += 1

                  val (detState2, cut0) = onByte(detector, bounds, detState, b, len)
                  detState = detState2

                  val cut = cut0 || len >= bounds.maxBytes

                  if cut then
                    val rawCut = len

                    val cutLen =
                      detector match
                        case Detector.Delimiter(_, _, include) if !include && cut0 && rawCut >= delimLen =>
                          rawCut - delimLen
                        case _                                                                           =>
                          rawCut

                    // emit if non-empty; delimiter mode can legitimately produce an empty block when includeDelimiter=false
                    if cutLen > 0 then
                      val outArr = Array.ofDim[Byte](cutLen)
                      java.lang.System.arraycopy(buf, 0, outArr, 0, cutLen)
                      Block.fromChunk(Chunk.fromArray(outArr)) match
                        case Right(block) => out += block
                        case Left(msg)    => failure = Some(new IllegalArgumentException(msg))

                    // drop rawCut bytes (including delimiter if present), shift remainder down
                    val rem = len - rawCut
                    if rem > 0 then java.lang.System.arraycopy(buf, rawCut, buf, 0, rem)

                    len = rem
                    detState = 0
                  end if

                  idx += 1
                end while

                failure match
                  case Some(err) => ZIO.fail(err)
                  case None      => ZIO.succeed((St(buf, len, detState), out.result()))
              }
              .flatMap { case (st2, out) =>
                ZChannel.write(out) *> loop(st2)
              },
          err => ZChannel.fail(err),
          _ =>
            // end-of-stream: flush remaining bytes as a final block (if non-empty)
            ZChannel
              .fromZIO {
                if st0.len <= 0 then ZIO.succeed(Chunk.empty[Block])
                else
                  val outArr = Array.ofDim[Byte](st0.len)
                  java.lang.System.arraycopy(st0.buf, 0, outArr, 0, st0.len)
                  ZIO
                    .fromEither(Block.fromChunk(Chunk.fromArray(outArr)))
                    .mapBoth(msg => new IllegalArgumentException(msg), block => Chunk.single(block))
              }
              .flatMap(out => ZChannel.write(out) *> ZChannel.unit),
        )

      val st0 =
        St(
          buf = Array.ofDim[Byte](bounds.maxBytes),
          len = 0,
          detState = 0,
        )

      loop(st0)
    }

  private def onByte(detector: Detector, bounds: Bounds, s: Int, b: Byte, bufLen: Int): (Int, Boolean) =
    detector match
      case Detector.Fixed =>
        val s2  = s + 1
        val cut = s2 >= bounds.avgBytes
        (s2, cut)

      case Detector.Delimiter(delim, pi, _) =>
        // KMP matcher state: matched prefix length (j)
        var j = s
        while j > 0 && b != delim(j) do j = pi(j - 1)
        if b == delim(j) then j += 1

        val matched = j
        val cut     = matched == delim.length && bufLen >= bounds.minBytes

        val nextState = if matched == delim.length then pi(matched - 1) else matched
        (nextState, cut)

      case Detector.FastCdc(strongMask, normalMask) =>
        val bi = b & 0xff
        val h2 = (s << 1) + gear(bi)

        val cut =
          if bufLen < bounds.minBytes then false
          else if bufLen < bounds.avgBytes then (h2 & strongMask) == 0
          else (h2 & normalMask) == 0

        (h2, cut)

  private def kmpPrefix(p: Array[Byte]): Array[Int] =
    val pi = Array.ofDim[Int](p.length)
    var j  = 0
    var i  = 1
    while i < p.length do
      while j > 0 && p(i) != p(j) do j = pi(j - 1)
      if p(i) == p(j) then j += 1
      pi(i) = j
      i += 1
    pi

  private def fastCdcMasks(avgBytes: Int): (Int, Int) =
    val avg        = math.max(avgBytes, 1)
    val bits       = 31 - Integer.numberOfLeadingZeros(avg)
    val normalBits = math.max(8, math.min(20, bits))
    val strongBits = math.max(6, math.min(18, bits - 1))
    val normalMask = (1 << normalBits) - 1
    val strongMask = (1 << strongBits) - 1
    (strongMask, normalMask)

  private lazy val gear: Array[Int] =
    val g = Array.ofDim[Int](256)
    var i = 0
    var x = 0x9e3779b9
    while i < 256 do
      x = Integer.rotateLeft(x ^ (i * 0x85ebca6b), 13) * 0xc2b2ae35
      g(i) = x
      i += 1
    g
