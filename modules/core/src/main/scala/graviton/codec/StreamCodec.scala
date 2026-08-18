package graviton.codec

import zio.*
import zio.stream.*
import zio.prelude.fx.ZPure
import zio.ChunkBuilder

/**
 * A state machine for streaming codecs built on top of [[ZPure]].
 *
 * The implementation keeps track of leftover bytes, recoverable decoding
 * failures, stream completion and optional diagnostic logs entirely in the
 * pure state.  Each decoding step emits [[Take]] values so that downstream
 * consumers can recover from insufficient input by continuing to push more
 * chunks into the channel.
 */
final class StreamCodec[+A](
  val initial: StreamCodecState,
  val onChunk: Chunk[Byte] => StreamCodec.CodecPure[Chunk[Take[StreamCodecError, A]]],
  val onEnd: StreamCodec.CodecPure[Chunk[Take[StreamCodecError, A]]],
):

  /**
   * Convert this codec into a [[ZChannel]].
   */
  def toChannel: ZChannel[Any, Nothing, Chunk[Byte], Any, Nothing, Take[StreamCodecError, A], Any] =
    def loop(state: StreamCodecState): ZChannel[Any, Nothing, Chunk[Byte], Any, Nothing, Take[StreamCodecError, A], Any] =
      state.status match
        case StreamStatus.Completed     =>
          ZChannel.write(Take.end) *> ZChannel.unit
        case StreamStatus.Failed(error) =>
          ZChannel.write(Take.fail(error)) *> ZChannel.unit
        case StreamStatus.Running       =>
          ZChannel.readWith(
            chunk =>
              val (logs, result)     = onChunk(chunk).runAll(state)
              val (nextState, takes) = result match
                case Right((st, out)) => (st.recordLogs(logs), out)
                case Left(_)          => (state, Chunk.empty)
              emitAll(takes) *> loop(nextState)
            ,
            cause => ZChannel.refailCause(cause),
            _ =>
              val (logs, result)     = onEnd.runAll(state)
              val (nextState, takes) = result match
                case Right((st, out)) => (st.recordLogs(logs), out)
                case Left(_)          => (state, Chunk.empty)
              emitAll(takes),
          )
    loop(initial)

  private def emitAll(takes: Chunk[Take[StreamCodecError, A]]): ZChannel[Any, Nothing, Any, Any, Nothing, Take[StreamCodecError, A], Any] =
    def emitAt(idx: Int): ZChannel[Any, Nothing, Any, Any, Nothing, Take[StreamCodecError, A], Any] =
      if idx >= takes.length then ZChannel.unit
      else ZChannel.write(takes(idx)) *> emitAt(idx + 1)
    emitAt(0)

object StreamCodec:
  type CodecPure[+A] = ZPure[CodecLogEntry, StreamCodecState, StreamCodecState, Any, Nothing, A]

  /**
   * Construct a codec that decodes fixed-size records using the supplied
   * decoding function.  Failures in the decoder are treated as fatal, while
   * insufficient input is recorded as a recoverable condition so that the next
   * chunk can attempt the decode again.
   */
  def fixedSize[A](
    size: Int
  )(decode: Chunk[Byte] => Either[String, A]): StreamCodec[A] =
    require(size > 0, "record size must be strictly positive")
    val initialState = StreamCodecState.empty
    new StreamCodec(
      initialState,
      chunk =>
        for
          _       <- ZPure.log(CodecLogEntry.received(chunk.length))
          outputs <- ZPure.modify { (state: StreamCodecState) =>
                       val appended             = state.appendInput(chunk)
                       val (updated, emissions) = decodeAvailable(appended, size, decode)
                       (emissions, updated)
                     }
        yield outputs,
      for outputs <- ZPure.modify { (state: StreamCodecState) =>
                       val (updated, emissions) = finishDecoding(state, size)
                       (emissions, updated)
                     }
      yield outputs,
    )

  private[codec] def decodeAvailable[A](
    state: StreamCodecState,
    size: Int,
    decode: Chunk[Byte] => Either[String, A],
  ): (StreamCodecState, Chunk[Take[StreamCodecError, A]]) =
    val builder          = ChunkBuilder.make[Take[StreamCodecError, A]]()
    var working          = state.prepareForDecode
    var continueDecoding = true

    while continueDecoding && working.buffer.length >= size do
      val (record, remainder) = working.buffer.splitAt(size)
      decode(record) match
        case Left(message) =>
          val error = StreamCodecError.DecoderFailure(message)
          working = working.withBuffer(remainder).recordFatal(error)
          builder += Take.fail(error)
          continueDecoding = false
        case Right(value)  =>
          working = working
            .withBuffer(remainder)
            .recordEmission(value)
          builder += Take.chunk(Chunk.single(value))
    if continueDecoding then
      if working.buffer.nonEmpty then working = working.markInsufficientInput(size)
      else working = working.clearRecoverable
    (working, builder.result())

  private[codec] def finishDecoding[A](
    state: StreamCodecState,
    size: Int,
  ): (StreamCodecState, Chunk[Take[StreamCodecError, A]]) =
    val builder = ChunkBuilder.make[Take[StreamCodecError, A]]()
    val next    = state.status match
      case StreamStatus.Completed => state
      case StreamStatus.Failed(_) => state
      case StreamStatus.Running   =>
        if state.buffer.isEmpty then
          builder += Take.end
          state.complete
        else
          val error = StreamCodecError.UnexpectedEnd(state.buffer.length, size)
          builder += Take.fail(error)
          builder += Take.end
          state.recordFatal(error).clearBuffer
    (next, builder.result())

final case class StreamCodecState(
  buffer: Chunk[Byte],
  status: StreamStatus,
  flags: Set[CodecFlag],
  logs: Chunk[CodecLogEntry],
  stats: CodecStats,
  lastFailure: Option[CodecFailure],
):

  def appendInput(chunk: Chunk[Byte]): StreamCodecState =
    copy(
      buffer = buffer ++ chunk,
      stats = stats.recordBuffered(chunk.length),
      flags = flags - CodecFlag.NeedsMoreInput,
      lastFailure = lastFailure.filterNot(_.recoverable),
      logs = logs :+ CodecLogEntry.received(chunk.length),
    )

  def clearBuffer: StreamCodecState = copy(buffer = Chunk.empty)

  def clearRecoverable: StreamCodecState =
    copy(
      flags = flags - CodecFlag.NeedsMoreInput,
      lastFailure = lastFailure.filterNot(_.recoverable),
    )

  def complete: StreamCodecState =
    copy(
      status = StreamStatus.Completed,
      flags = (flags - CodecFlag.NeedsMoreInput) + CodecFlag.Completed,
      logs = logs :+ CodecLogEntry.completed,
    )

  def markInsufficientInput(required: Int): StreamCodecState =
    val error = StreamCodecError.InsufficientBytes(required, buffer.length)
    copy(
      flags = flags + CodecFlag.NeedsMoreInput,
      stats = stats.recordRecoverableFailure,
      lastFailure = Some(CodecFailure(error, recoverable = true)),
      logs = logs :+ CodecLogEntry.insufficient(buffer.length, required),
    )

  def prepareForDecode: StreamCodecState =
    copy(
      flags = flags - CodecFlag.NeedsMoreInput,
      lastFailure = lastFailure.filterNot(_.recoverable),
    )

  def recordEmission[A](value: A): StreamCodecState =
    copy(
      stats = stats.recordEmission,
      logs = logs :+ CodecLogEntry.decoded(value.toString, buffer.length),
    )

  def recordFatal(error: StreamCodecError): StreamCodecState =
    copy(
      status = StreamStatus.Failed(error),
      flags = flags + CodecFlag.Failed,
      stats = stats.recordFatalFailure,
      lastFailure = Some(CodecFailure(error, recoverable = false)),
      logs = logs :+ CodecLogEntry.fatal(error.getMessage),
    )

  def recordLogs(entries: Chunk[CodecLogEntry]): StreamCodecState =
    if entries.isEmpty then this else copy(logs = logs ++ entries)

  def withBuffer(newBuffer: Chunk[Byte]): StreamCodecState = copy(buffer = newBuffer)

object StreamCodecState:
  val empty: StreamCodecState = StreamCodecState(
    buffer = Chunk.empty,
    status = StreamStatus.Running,
    flags = Set.empty,
    logs = Chunk.empty,
    stats = CodecStats.empty,
    lastFailure = None,
  )

final case class CodecStats(
  bufferedBytes: Long,
  emittedValues: Long,
  recoverableFailures: Long,
  fatalFailures: Long,
):
  def recordBuffered(n: Int): CodecStats   = copy(bufferedBytes = bufferedBytes + n.toLong)
  def recordEmission: CodecStats           = copy(emittedValues = emittedValues + 1)
  def recordRecoverableFailure: CodecStats = copy(recoverableFailures = recoverableFailures + 1)
  def recordFatalFailure: CodecStats       = copy(fatalFailures = fatalFailures + 1)

object CodecStats:
  val empty: CodecStats = CodecStats(0L, 0L, 0L, 0L)

final case class CodecFailure(error: StreamCodecError, recoverable: Boolean)

enum CodecFlag:
  case NeedsMoreInput, Completed, Failed

enum StreamStatus:
  case Running
  case Completed
  case Failed(error: StreamCodecError)

enum CodecLogEntry:
  case Received(bytes: Int)
  case Insufficient(available: Int, required: Int)
  case Decoded(value: String, remaining: Int)
  case Fatal(message: String)
  case Completed

object CodecLogEntry:
  def received(bytes: Int): CodecLogEntry                   = CodecLogEntry.Received(bytes)
  def insufficient(available: Int, required: Int)           = CodecLogEntry.Insufficient(available, required)
  def decoded(value: String, remaining: Int): CodecLogEntry = CodecLogEntry.Decoded(value, remaining)
  def fatal(message: String): CodecLogEntry                 = CodecLogEntry.Fatal(message)
  val completed: CodecLogEntry                              = CodecLogEntry.Completed

sealed trait StreamCodecError extends RuntimeException:
  override def getMessage: String = this match
    case StreamCodecError.InsufficientBytes(required, available) =>
      s"insufficient bytes: required=$required available=$available"
    case StreamCodecError.UnexpectedEnd(remaining, expectedSize) =>
      s"unexpected end of stream: remaining=$remaining expectedSize=$expectedSize"
    case StreamCodecError.DecoderFailure(message)                => s"decoder failure: $message"

object StreamCodecError:
  final case class InsufficientBytes(required: Int, available: Int) extends StreamCodecError
  final case class UnexpectedEnd(remaining: Int, expectedSize: Int) extends StreamCodecError
  final case class DecoderFailure(message: String)                  extends StreamCodecError
