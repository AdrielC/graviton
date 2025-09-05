package graviton.chunking

import zio.*
import zio.stream.*

object FastCDCChunker:
  import Chunker.Bounds

  final case class Config(
    bounds: Bounds,
    normalization: Int = 2,
    window: Int = 64,
  )

  private val gearTable: Array[Int] =
    val arr  = new Array[Int](256)
    var seed = 0x12345678
    var i    = 0
    while i < 256 do
      seed = (seed * 1664525 + 1013904223) & 0xffffffff
      arr(i) = seed
      i += 1
    arr

  def apply(cfg: Config): Chunker =
    new Chunker:
      val name                                                   =
        s"fastcdc(min=${cfg.bounds.min},avg=${cfg.bounds.avg},max=${cfg.bounds.max})"
      val pipeline: ZPipeline[Any, Throwable, Byte, Chunk[Byte]] =
        ZPipeline.fromChannel:
          def loop(buf: Chunk[Byte]): ZChannel[Any, Throwable, Chunk[
            Byte
          ], Any, Throwable, Chunk[Chunk[Byte]], Any] =
            ZChannel.readWith(
              (in: Chunk[Byte]) => loop(buf ++ in),
              (err: Throwable) => ZChannel.fail(err),
              (_: Any) => ZChannel.write(split(buf, cfg)),
            )
          loop(Chunk.empty)

  private def split(bytes: Chunk[Byte], cfg: Config): Chunk[Chunk[Byte]] =
    val bits       = math.round(math.log(cfg.bounds.avg.toDouble) / math.log(2)).toInt
    val maskS      = (1 << (bits + 1)) - 1
    val maskL      = (1 << (bits - 1)) - 1
    val centerSize = cfg.bounds.avg - math.min(
      cfg.bounds.avg,
      cfg.bounds.min + (cfg.bounds.min + 1) / 2,
    )
    val chunks     = scala.collection.mutable.ListBuffer.empty[Chunk[Byte]]
    var offset     = 0
    while offset < bytes.length do
      val remaining = bytes.length - offset
      if remaining <= cfg.bounds.min then
        chunks += bytes.drop(offset)
        offset = bytes.length
      else
        val realSize = math.min(remaining, cfg.bounds.max)
        val bound1   = offset + math.min(centerSize, realSize)
        val bound2   = offset + realSize
        var hash     = 0
        var pos      = offset + cfg.bounds.min
        var cut      = 0
        while pos < bound1 && cut == 0 do
          val b = bytes(pos) & 0xff
          hash = (hash >>> 1) + gearTable(b)
          if (hash & maskS) == 0 then cut = pos - offset
          pos += 1
        while pos < bound2 && cut == 0 do
          val b = bytes(pos) & 0xff
          hash = (hash >>> 1) + gearTable(b)
          if (hash & maskL) == 0 then cut = pos - offset
          pos += 1
        if cut == 0 then cut = realSize
        chunks += bytes.slice(offset, offset + cut)
        offset += cut
    Chunk.fromIterable(chunks)
