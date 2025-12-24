package graviton.http

import zio.*
import zio.stream.*
import zio.stream.Take
import zio.ChunkBuilder
import graviton.core.scan.*
import graviton.core.scan.FS.*

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/**
 * Incremental HTTP/1.1 chunked-transfer decoder expressed as a [[graviton.Scan]].
 */
object HttpChunkedScan {

  private val Cr: Byte = 13.toByte
  private val Lf: Byte = 10.toByte

  sealed trait Phase
  object Phase {
    case object ReadingSize                      extends Phase
    final case class ReadingBody(remaining: Int) extends Phase
    case object ExpectingBodyCr                  extends Phase
    case object ExpectingBodyLf                  extends Phase
    case object ReadingTrailers                  extends Phase
    case object Done                             extends Phase
  }

  final case class ChunkedDecodeError(message: String) extends Exception(message)

  inline private def ascii(bytes: Chunk[Byte]): String =
    new String(bytes.toArray, StandardCharsets.US_ASCII)

  inline private def parseSizeLine(line: Chunk[Byte]): Either[ChunkedDecodeError, Int] = {
    val semi   = line.indexWhere(_ == ';')
    val hex    = if semi >= 0 then line.take(semi) else line
    val parsed = ascii(hex).trim
    try Right(Integer.parseInt(parsed, 16))
    catch case NonFatal(_) => Left(ChunkedDecodeError(s"Invalid chunk-size line: '$parsed'"))
  }

  final case class DecoderState(
    phase: Phase,
    lineBuf: Chunk[Byte],
    bodyBuf: Array[Byte],
    bodyIndex: Int,
    window: Int,
  )

  inline private def advanceWindow(window: Int, b: Byte): Int =
    ((window << 8) | (b & 0xff)) & 0xffff

  private def stepDecode(
    state: DecoderState,
    b: Byte,
  ): (DecoderState, Chunk[Take[Throwable, Chunk[Byte]]]) =
    val DecoderState(phase, lineBuf, bodyBuf, bodyIndex, window) = state
    val nextWindow                                               = advanceWindow(window, b)

    phase match
      case Phase.ReadingSize =>
        if (nextWindow & 0xffff) == 0x0d0a then
          parseSizeLine(lineBuf) match
            case Left(err)   =>
              (DecoderState(Phase.Done, Chunk.empty, bodyBuf, 0, 0), Chunk(Take.fail(err)))
            case Right(0)    =>
              (DecoderState(Phase.ReadingTrailers, Chunk.empty, Array.emptyByteArray, 0, 0), Chunk.empty)
            case Right(size) =>
              val buffer = Array.ofDim[Byte](size)
              (DecoderState(Phase.ReadingBody(size), Chunk.empty, buffer, 0, 0), Chunk.empty)
        else
          val nextBuf = if b == Cr || b == Lf then lineBuf else lineBuf :+ b
          (DecoderState(Phase.ReadingSize, nextBuf, bodyBuf, bodyIndex, nextWindow & 0xffff), Chunk.empty)

      case Phase.ReadingBody(remaining) =>
        bodyBuf(bodyIndex) = b
        val nextRemaining = remaining - 1
        val nextIndex     = bodyIndex + 1
        if nextRemaining == 0 then (DecoderState(Phase.ExpectingBodyCr, Chunk.empty, bodyBuf, nextIndex, 0), Chunk.empty)
        else (DecoderState(Phase.ReadingBody(nextRemaining), Chunk.empty, bodyBuf, nextIndex, 0), Chunk.empty)

      case Phase.ExpectingBodyCr =>
        if b == Cr then (DecoderState(Phase.ExpectingBodyLf, Chunk.empty, bodyBuf, bodyIndex, 0), Chunk.empty)
        else
          (
            DecoderState(Phase.Done, Chunk.empty, bodyBuf, bodyIndex, 0),
            Chunk(Take.fail(ChunkedDecodeError("Missing CR after chunk body"))),
          )

      case Phase.ExpectingBodyLf =>
        if b == Lf then
          val out = Chunk.fromArray(java.util.Arrays.copyOf(bodyBuf, bodyIndex))
          (DecoderState(Phase.ReadingSize, Chunk.empty, Array.emptyByteArray, 0, 0), Chunk(Take.single(out)))
        else
          (
            DecoderState(Phase.Done, Chunk.empty, bodyBuf, bodyIndex, 0),
            Chunk(Take.fail(ChunkedDecodeError("Missing LF after chunk body"))),
          )

      case Phase.ReadingTrailers =>
        if (nextWindow & 0xffff) == 0x0d0a then
          if lineBuf.isEmpty then (DecoderState(Phase.Done, Chunk.empty, bodyBuf, bodyIndex, 0), Chunk.empty)
          else (DecoderState(Phase.ReadingTrailers, Chunk.empty, bodyBuf, 0, 0), Chunk.empty)
        else
          val nextBuf = if b == Cr then lineBuf else lineBuf :+ b
          (DecoderState(Phase.ReadingTrailers, nextBuf, bodyBuf, bodyIndex, nextWindow & 0xffff), Chunk.empty)

      case Phase.Done =>
        (DecoderState(Phase.Done, lineBuf, bodyBuf, bodyIndex, nextWindow), Chunk.empty)

  private def flushDecode(state: DecoderState): Chunk[Take[Throwable, Chunk[Byte]]] =
    state.phase match
      case Phase.Done            => Chunk.empty
      case Phase.ReadingSize     => Chunk(Take.fail(ChunkedDecodeError("Unexpected EOF while reading chunk size")))
      case Phase.ReadingBody(_)  => Chunk(Take.fail(ChunkedDecodeError("Unexpected EOF inside chunk body")))
      case Phase.ExpectingBodyCr => Chunk(Take.fail(ChunkedDecodeError("Unexpected EOF after chunk body")))
      case Phase.ExpectingBodyLf => Chunk(Take.fail(ChunkedDecodeError("Unexpected EOF after chunk body")))
      case Phase.ReadingTrailers => Chunk(Take.fail(ChunkedDecodeError("Unexpected EOF while reading chunk trailers")))

  val chunkedDecode: FreeScan[Prim, Byte, Take[Throwable, Chunk[Byte]]] =
    FS.fold[Byte, Take[Throwable, Chunk[Byte]], DecoderState](
      DecoderState(Phase.ReadingSize, Chunk.empty, Array.emptyByteArray, 0, 0)
    )((state, byte) => stepDecode(state, byte))(flushDecode)

  val chunkedPipeline: ZPipeline[Any, Throwable, Byte, Byte] =
    chunkedDecode.toPipeline >>> ZPipeline.mapChunksZIO { takes =>
      val builder = ChunkBuilder.make[Byte]()
      var idx     = 0
      var failure = Option.empty[Throwable]
      while (idx < takes.length && failure.isEmpty) do
        takes(idx) match
          case take: Take[Throwable, Chunk[Byte]] @unchecked =>
            take.exit match
              case Exit.Success(values) =>
                values.foreach(builder ++= _)
              case Exit.Failure(cause)  =>
                failure = cause.failureOption.flatten
        idx += 1
      failure match
        case Some(err) => ZIO.fail(err)
        case None      => ZIO.succeed(builder.result())
    }
}
