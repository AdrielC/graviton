package graviton.http

import zio.*
import zio.stream.*
import zio.ChunkBuilder
import graviton.Scan

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/**
 * Incremental HTTP/1.1 chunked-transfer decoder expressed as a [[graviton.Scan]].
 *
 * The decoder is allocation-friendly and emits a [[zio.Chunk]] for every completed
 * body chunk. Trailers are discarded; callers that need to surface them can build a
 * derived scan that inspects the internal state or extends the emitted value.
 */
object HttpChunkedScan:

  private val Cr: Byte = 13.toByte
  private val Lf: Byte = 10.toByte

  sealed trait Phase
  object Phase:
    case object ReadingSize                      extends Phase
    final case class ReadingBody(remaining: Int) extends Phase
    case object ReadingBodyCrLf                  extends Phase
    case object ReadingTrailers                  extends Phase
    case object Done                             extends Phase

  inline private def ascii(bytes: Chunk[Byte]): String =
    new String(bytes.toArray, StandardCharsets.US_ASCII)

  inline private def parseSizeLine(line: Chunk[Byte]): Int =
    val semi = line.indexWhere(_ == ';')
    val hex  = if semi >= 0 then line.take(semi) else line
    Integer.parseInt(ascii(hex).trim, 16)

  type DecoderState = (Phase, Chunk[Byte], ChunkBuilder[Byte], Int)

  /**
   * Scan that decodes HTTP chunked transfer encoding. The scan emits a chunk of
   * bytes each time a chunk body is completed. Once the terminal chunk and
   * trailing CRLF are processed the scan transitions into a "done" state and
   * ignores further input.
   */
  val chunkedDecode: Scan.Aux[Byte, Chunk[Byte], DecoderState *: EmptyTuple] =
    Scan.stateful[Byte, Chunk[Byte], (Phase, Chunk[Byte], ChunkBuilder[Byte], Int)](
      (Phase.ReadingSize, Chunk.empty[Byte], ChunkBuilder.make[Byte](), 0)
    ) { (st, b) =>
      val (phase, lineBuf, bodyBuf, window) = st
      phase match
        case Phase.ReadingSize =>
          if lineBuf.nonEmpty && lineBuf.last == Cr && b == Lf then
            val line = lineBuf.dropRight(1)
            val size =
              try parseSizeLine(line)
              catch
                case NonFatal(_) =>
                  throw new RuntimeException(s"Invalid chunk-size line: '${ascii(line)}'")

            if size == 0 then ((Phase.ReadingTrailers, Chunk.empty[Byte], bodyBuf, 0), Chunk.empty[Chunk[Byte]])
            else
              bodyBuf.clear()
              ((Phase.ReadingBody(size), Chunk.empty[Byte], bodyBuf, 0), Chunk.empty[Chunk[Byte]])
          else ((Phase.ReadingSize, lineBuf :+ b, bodyBuf, 0), Chunk.empty[Chunk[Byte]])

        case Phase.ReadingBody(remaining) =>
          bodyBuf += b
          val nextRemaining = remaining - 1
          if nextRemaining == 0 then ((Phase.ReadingBodyCrLf, lineBuf, bodyBuf, 0), Chunk.empty[Chunk[Byte]])
          else ((Phase.ReadingBody(nextRemaining), lineBuf, bodyBuf, 0), Chunk.empty[Chunk[Byte]])

        case Phase.ReadingBodyCrLf =>
          if lineBuf.isEmpty && b == Cr then ((Phase.ReadingBodyCrLf, Chunk.single(Cr), bodyBuf, 0), Chunk.empty[Chunk[Byte]])
          else if lineBuf.length == 1 && lineBuf(0) == Cr && b == Lf then
            val out = Chunk.fromArray(bodyBuf.result().toArray)
            bodyBuf.clear()
            ((Phase.ReadingSize, Chunk.empty[Byte], bodyBuf, 0), Chunk.single(out))
          else throw new RuntimeException("Invalid chunk: missing CRLF after data")

        case Phase.ReadingTrailers =>
          val nextBuf = lineBuf :+ b
          if nextBuf.lengthCompare(2) >= 0 && nextBuf(nextBuf.length - 2) == Cr && nextBuf.last == Lf then
            val line = nextBuf.dropRight(2)
            if line.isEmpty then ((Phase.Done, Chunk.empty[Byte], bodyBuf, 0), Chunk.empty[Chunk[Byte]])
            else ((Phase.ReadingTrailers, Chunk.empty[Byte], bodyBuf, 0), Chunk.empty[Chunk[Byte]])
          else ((Phase.ReadingTrailers, nextBuf, bodyBuf, 0), Chunk.empty[Chunk[Byte]])

        case Phase.Done =>
          ((Phase.Done, lineBuf, bodyBuf, window), Chunk.empty[Chunk[Byte]])
    } { st =>
      st._1 match
        case Phase.Done            => Chunk.empty[Chunk[Byte]]
        case Phase.ReadingSize     => Chunk.empty[Chunk[Byte]]
        case Phase.ReadingBody(_)  =>
          throw new RuntimeException("Unexpected EOF inside chunk body")
        case Phase.ReadingBodyCrLf =>
          throw new RuntimeException("Unexpected EOF after chunk body")
        case Phase.ReadingTrailers =>
          throw new RuntimeException("Unexpected EOF while reading trailers")
    }

  /** Convenience pipeline that emits the raw bytes of the decoded body. */
  val chunkedPipeline: ZPipeline[Any, Throwable, Byte, Byte] =
    chunkedDecode.toPipeline >>> ZPipeline.mapChunks { (chunked: Chunk[Chunk[Byte]]) =>
      chunked.foldLeft(Chunk.empty[Byte]) { (acc, piece) =>
        acc ++ piece
      }
    }

end HttpChunkedScan
