package graviton.chunking

import graviton.core.model.Block
import zio.*
import zio.stream.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.InflaterInputStream
import graviton.GravitonError


/**
 * Chunker that splits a PDF on `stream` boundaries and emits uncompressed
 * stream data.
 */
object PdfChunker extends Chunker:
  val name                                             = "pdf"
  val pipeline: ZPipeline[Any, GravitonError, Byte, Block] =
    ZPipeline
      .fromChannel {
        def loop(buf: Chunk[Byte]): ZChannel[Any, Throwable, Chunk[
          Byte
        ], Any, Throwable, Chunk[Chunk[Byte]], Any] =
          ZChannel.readWith(
            (in: Chunk[Byte]) => loop(buf ++ in),
            (err: Throwable) => ZChannel.fail(err),
            (_: Any) => ZChannel.write(split(buf)),
          )
        loop(Chunk.empty)
      }
      .mapChunksZIO { chunked =>
        ZIO.foreach(chunked)(bytes => ZIO.fromEither(Block.fromChunk(bytes)).mapError(err => new IllegalArgumentException(err)))
      }
      .mapError(err => GravitonError.ChunkerFailure(err.getMessage))

  private val streamToken    = "stream".getBytes("ISO-8859-1")
  private val endStreamToken = "endstream".getBytes("ISO-8859-1")

  private def indexOf(src: Array[Byte], tgt: Array[Byte], from: Int): Int =
    var i = from
    while i <= src.length - tgt.length do
      var j = 0
      while j < tgt.length && src(i + j) == tgt(j) do j += 1
      if j == tgt.length then return i
      i += 1
    -1

  private def split(bytes: Chunk[Byte]): Chunk[Chunk[Byte]] =
    val arr    = bytes.toArray
    val out    = scala.collection.mutable.ListBuffer.empty[Chunk[Byte]]
    var cursor = 0
    while cursor < arr.length do
      val streamIdx = indexOf(arr, streamToken, cursor)
      if streamIdx == -1 then
        out += Chunk.fromArray(arr.slice(cursor, arr.length))
        cursor = arr.length
      else
        if streamIdx > cursor then out += Chunk.fromArray(arr.slice(cursor, streamIdx))
        var dataStart = streamIdx + streamToken.length
        if dataStart < arr.length && (arr(dataStart) == '\n' || arr(
            dataStart
          ) == '\r')
        then
          dataStart += 1
          if dataStart < arr.length && arr(dataStart - 1) == '\r' && arr(
              dataStart
            ) == '\n'
          then dataStart += 1
        val endIdx    = indexOf(arr, endStreamToken, dataStart)
        if endIdx == -1 then
          out += Chunk.fromArray(arr.slice(dataStart, arr.length))
          cursor = arr.length
        else
          val raw          = arr.slice(dataStart, endIdx)
          val decompressed =
            try
              val in  = new InflaterInputStream(new ByteArrayInputStream(raw))
              val buf = new ByteArrayOutputStream()
              val tmp = new Array[Byte](1024)
              var n   = in.read(tmp)
              while n > 0 do
                buf.write(tmp, 0, n)
                n = in.read(tmp)
              buf.toByteArray
            catch case _: Throwable => raw
          out += Chunk.fromArray(decompressed)
          cursor = endIdx + endStreamToken.length
    Chunk.fromIterable(out)
