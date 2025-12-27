package graviton.streams

import graviton.core.model.Block
import zio.{Chunk, ChunkBuilder}

/**
 * A plain, incremental chunking state machine.
 *
 * - No ZIO effects
 * - No ZPipeline/ZChannel
 * - Can be lifted into streams, or run directly on `Chunk[Byte]`
 */
object ChunkerCore:

  sealed trait Err extends Product with Serializable
  object Err:
    case object EmptyDelimiter                               extends Err
    final case class InvalidBounds(message: String)          extends Err
    final case class InvalidBlock(message: String)           extends Err
    final case class InvalidDelimiter(message: String)       extends Err

  enum Mode:
    case Fixed(chunkBytes: Int)
    case Delimiter(delim: Chunk[Byte], includeDelimiter: Boolean, minBytes: Int, maxBytes: Int)
    case FastCdc(minBytes: Int, avgBytes: Int, maxBytes: Int)

  final case class State private[streams] (
    private val bounds: Bounds,
    private val detector: Detector,
    private val delimLen: Int,
    private val buf: Array[Byte],
    private val len: Int,
    private val detState: Int,
  ):
    /**
     * Feed more input bytes.
     *
     * Returns (newState, emittedBlocks).
     */
    def step(in: Chunk[Byte]): Either[Err, (State, Chunk[Block])] =
      val out = ChunkBuilder.make[Block]()

      val buf0 = buf
      var len0 = len
      var s0   = detState

      var idx = 0
      while idx < in.length do
        val b = in(idx)

        buf0(len0) = b
        len0 += 1

        val (s1, cut0) = onByte(detector, bounds, s0, b, len0)
        s0 = s1

        val cut = cut0 || len0 >= bounds.maxBytes

        if cut then
          val rawCut = len0

          val cutLen =
            detector match
              case Detector.Delimiter(_, _, include) if !include && cut0 && rawCut >= delimLen =>
                rawCut - delimLen
              case _                                                                           =>
                rawCut

          // delimiter exclude-mode can yield empty blocks; just drop them.
          if cutLen > 0 then
            val outArr = Array.ofDim[Byte](cutLen)
            java.lang.System.arraycopy(buf0, 0, outArr, 0, cutLen)
            Block.fromChunk(Chunk.fromArray(outArr)) match
              case Right(block) => out += block
              case Left(msg)    => return Left(Err.InvalidBlock(msg))

          // Always consume the whole buffered region, including delimiter bytes (if any).
          // This keeps the implementation simple and bounded.
          len0 = 0
          s0 = 0
        end if

        idx += 1
      end while

      Right(copy(len = len0, detState = s0), out.result())

    /**
     * End-of-input flush.
     *
     * Returns (newState, finalBlocks) where newState is reset.
     */
    def finish: Either[Err, (State, Chunk[Block])] =
      if len <= 0 then Right(copy(len = 0, detState = 0), Chunk.empty)
      else
        val outArr = Array.ofDim[Byte](len)
        java.lang.System.arraycopy(buf, 0, outArr, 0, len)
        Block
          .fromChunk(Chunk.fromArray(outArr))
          .left
          .map(Err.InvalidBlock.apply)
          .map { block =>
            (copy(len = 0, detState = 0), Chunk.single(block))
          }

  def init(mode: Mode): Either[Err, State] =
    mode match
      case Mode.Fixed(chunkBytes) =>
        val size = math.max(1, math.min(chunkBytes, Block.maxBytes))
        val bounds = Bounds(minBytes = size, avgBytes = size, maxBytes = size)
        Right(
          State(
            bounds = bounds,
            detector = Detector.Fixed,
            delimLen = 0,
            buf = Array.ofDim[Byte](bounds.maxBytes),
            len = 0,
            detState = 0,
          )
        )

      case Mode.Delimiter(delim, includeDelimiter, minBytes, maxBytes) =>
        if delim.isEmpty then Left(Err.EmptyDelimiter)
        else
          val delimArr = delim.toArray
          val safeMin = math.max(1, minBytes)
          val safeMax = math.max(safeMin, math.min(maxBytes, Block.maxBytes))
          val bounds  = Bounds(minBytes = safeMin, avgBytes = safeMin, maxBytes = safeMax)
          Right(
            State(
              bounds = bounds,
              detector = Detector.Delimiter(delimArr, kmpPrefix(delimArr), includeDelimiter),
              delimLen = delimArr.length,
              buf = Array.ofDim[Byte](bounds.maxBytes),
              len = 0,
              detState = 0,
            )
          )

      case Mode.FastCdc(minBytes, avgBytes, maxBytes) =>
        val safeMin = math.max(1, minBytes)
        val safeMax = math.max(safeMin, math.min(maxBytes, Block.maxBytes))
        val safeAvg = math.max(safeMin, math.min(avgBytes, safeMax))
        val (strongMask, normalMask) = fastCdcMasks(safeAvg)
        val bounds = Bounds(minBytes = safeMin, avgBytes = safeAvg, maxBytes = safeMax)
        Right(
          State(
            bounds = bounds,
            detector = Detector.FastCdc(strongMask = strongMask, normalMask = normalMask),
            delimLen = 0,
            buf = Array.ofDim[Byte](bounds.maxBytes),
            len = 0,
            detState = 0,
          )
        )

  // ------------------------------------------
  // Internals (pure + local mutation only)
  // ------------------------------------------

  private[streams] final case class Bounds(minBytes: Int, avgBytes: Int, maxBytes: Int)

  private[streams] sealed trait Detector
  private[streams] object Detector:
    case object Fixed                                                                extends Detector
    final case class Delimiter(delim: Array[Byte], pi: Array[Int], include: Boolean) extends Detector
    final case class FastCdc(strongMask: Int, normalMask: Int)                       extends Detector

  private def onByte(detector: Detector, bounds: Bounds, s: Int, b: Byte, bufLen: Int): (Int, Boolean) =
    detector match
      case Detector.Fixed =>
        val s2  = s + 1
        val cut = s2 >= bounds.avgBytes
        (s2, cut)

      case Detector.Delimiter(delim, pi, _) =>
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

