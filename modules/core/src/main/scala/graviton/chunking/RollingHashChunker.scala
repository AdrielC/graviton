package graviton.chunking

import graviton.core.model.Block
import zio.*
import zio.stream.*

object RollingHashChunker:
  import Chunker.Bounds

  final case class Config(
    bounds: Bounds,
    window: Int = 48,
    polynomial: Long = 0x3da3358b4dc173L,
  )

  def apply(cfg: Config): Chunker =
    new Chunker:
      val name                                       =
        s"rolling(min=${cfg.bounds.min},avg=${cfg.bounds.avg},max=${cfg.bounds.max})"
      val pipeline: ZPipeline[Any, Throwable, Byte, Block] =
        ZPipeline
          .fromChannel {
            def loop(state: State): ZChannel[Any, Throwable, Chunk[
              Byte
          ], Any, Throwable, Chunk[Chunk[Byte]], Any] =
              ZChannel.readWith(
                (in: Chunk[Byte]) =>
                  val (next, out) = process(state, in, cfg)
                  ZChannel.write(out) *> loop(next)
                ,
                (err: Throwable) => ZChannel.fail(err),
                (_: Any) =>
                  if state.buffer.isEmpty then ZChannel.unit
                  else ZChannel.write(Chunk.single(state.buffer)),
              )
            loop(State.empty(cfg))
          }
          .mapChunksZIO { chunked =>
            ZIO.foreach(chunked)(bytes =>
              ZIO.fromEither(Block.fromChunk(bytes)).mapError(err => new IllegalArgumentException(err))
            )
          }

  private final case class State(
    cfg: Config,
    buffer: Chunk[Byte],
    window: Array[Byte],
    start: Int,
    len: Int,
    rolling: Long,
  )

  private object State:
    def empty(cfg: Config): State =
      State(cfg, Chunk.empty, new Array[Byte](cfg.window), 0, 0, 0L)

  private def addByte(s: State, b: Byte): State =
    if s.len < s.cfg.window then
      val win     = s.window.clone()
      win((s.start + s.len) % s.cfg.window) = b
      val rolling = s.rolling * s.cfg.polynomial + (b & 0xff)
      s.copy(buffer = s.buffer :+ b, window = win, len = s.len + 1, rolling = rolling)
    else
      val win      = s.window.clone()
      val ev       = s.window(s.start)
      val newStart = (s.start + 1) % s.cfg.window
      win((s.start + s.len) % s.cfg.window) = b
      val pow      = fastPow(s.cfg.polynomial, s.len)
      val without  = s.rolling - (ev & 0xff) * pow
      val rolling  = without * s.cfg.polynomial + (b & 0xff)
      s.copy(buffer = s.buffer :+ b, window = win, start = newStart, rolling = rolling)

  private def shouldCut(s: State): Boolean =
    val size = s.buffer.size
    if size < s.cfg.bounds.min then false
    else if size >= s.cfg.bounds.max then true
    else if s.len >= s.cfg.window then
      val bits = math.round(math.log(s.cfg.bounds.avg.toDouble) / math.log(2)).toInt
      val mask = (1L << (bits - 1).max(1)) - 1
      (s.rolling & mask) == 0
    else false

  private def process(
    init: State,
    in: Chunk[Byte],
    cfg: Config,
  ): (State, Chunk[Chunk[Byte]]) =
    var s   = init
    val out = scala.collection.mutable.ListBuffer.empty[Chunk[Byte]]
    var i   = 0
    while i < in.length do
      s = addByte(s, in(i))
      if shouldCut(s) then
        out += s.buffer
        s = State.empty(cfg)
      i += 1
    (s, Chunk.fromIterable(out))

  private def fastPow(base: Long, exp: Int): Long =
    @annotation.tailrec
    def loop(res: Long, b: Long, e: Int): Long =
      if e <= 0 then res
      else
        val res2 = if (e & 1) == 1 then res * b else res
        loop(res2, b * b, e >>> 1)
    loop(1L, base, exp)
