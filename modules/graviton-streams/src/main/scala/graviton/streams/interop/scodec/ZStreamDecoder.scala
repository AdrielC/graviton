package graviton.streams.interop.scodec

import zio.*
import zio.stream.*
import zio.ChunkBuilder

import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult, Decoder, Err}

final case class CodecError(err: Err) extends Exception(err.messageWithContext)

object ZStreamDecoder:
  final val DefaultMaxBufferedBytes                     = 32L * 1024 * 1024
  private[scodec] final val DefaultMaxOutputBatchValues = 1024

  def once[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    once(decoder, DefaultMaxBufferedBytes)

  def once[A](decoder: Decoder[A], maxBufferedBytes: Long): ZPipeline[Any, Throwable, BitVector, A] =
    decode(
      decoder,
      once = true,
      failOnError = true,
      maxBufferedBytes = maxBufferedBytes,
      maxOutputBatchValues = 1,
    )

  def many[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    many(decoder, DefaultMaxBufferedBytes)

  def many[A](decoder: Decoder[A], maxBufferedBytes: Long): ZPipeline[Any, Throwable, BitVector, A] =
    many(decoder, maxBufferedBytes, DefaultMaxOutputBatchValues)

  private[scodec] def many[A](
    decoder: Decoder[A],
    maxBufferedBytes: Long,
    maxOutputBatchValues: Int,
  ): ZPipeline[Any, Throwable, BitVector, A] =
    decode(
      decoder,
      once = false,
      failOnError = true,
      maxBufferedBytes = maxBufferedBytes,
      maxOutputBatchValues = maxOutputBatchValues,
    )

  /**
   * Decodes a sequence transported as bytes, where the final byte may contain
   * zero padding after the last value. Padding between values is never accepted.
   */
  private[graviton] def manyByteAligned[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    manyByteAligned(decoder, DefaultMaxBufferedBytes)

  private[graviton] def manyByteAligned[A](
    decoder: Decoder[A],
    maxBufferedBytes: Long,
  ): ZPipeline[Any, Throwable, BitVector, A] =
    decode(
      decoder,
      once = false,
      failOnError = true,
      maxBufferedBytes = maxBufferedBytes,
      maxOutputBatchValues = DefaultMaxOutputBatchValues,
      maxTrailingZeroBits = 7L,
    )

  def tryOnce[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    decode(
      decoder,
      once = true,
      failOnError = false,
      maxBufferedBytes = DefaultMaxBufferedBytes,
      maxOutputBatchValues = 1,
    )

  def tryMany[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    decode(
      decoder,
      once = false,
      failOnError = false,
      maxBufferedBytes = DefaultMaxBufferedBytes,
      maxOutputBatchValues = DefaultMaxOutputBatchValues,
    )

  // Retain the 0.4.0 compiler-emitted private shells so patch releases remain
  // bytecode-compatible even though the decoder no longer uses this state machine.
  private final case class DecoderState(
    buffer: BitVector,
    awaiting: Option[Err.InsufficientBits],
  )

  private object DecoderState:
    val empty: DecoderState = DecoderState(BitVector.empty, None)

  private final case class StepOutcome[A](values: Chunk[A], stop: Boolean)

  private final case class StreamState(
    buffer: BitVector,
    decodedAny: Boolean,
  )

  private object StreamState:
    val empty: StreamState = StreamState(BitVector.empty, decodedAny = false)

  private final case class PendingInput(
    source: Chunk[BitVector],
    nextIndex: Int,
    current: BitVector,
  ):
    def hasRemaining: Boolean = current.nonEmpty || nextIndex < source.length

  private object PendingInput:
    def apply(source: Chunk[BitVector]): PendingInput =
      PendingInput(source, nextIndex = 0, current = BitVector.empty)

  private final case class BatchOutcome[A](
    state: StreamState,
    pending: PendingInput,
    values: Chunk[A],
    resumeWithoutRead: Boolean,
    stop: Boolean,
    failure: Option[CodecError],
  )

  private def decode[A](
    decoder: Decoder[A],
    once: Boolean,
    failOnError: Boolean,
    maxBufferedBytes: Long,
    maxOutputBatchValues: Int,
    maxTrailingZeroBits: Long = 0L,
  ): ZPipeline[Any, Throwable, BitVector, A] =
    require(maxBufferedBytes > 0L, "maxBufferedBytes must be positive")
    require(maxBufferedBytes <= Long.MaxValue / 8L, "maxBufferedBytes is too large")
    require(maxOutputBatchValues > 0, "maxOutputBatchValues must be positive")
    require(maxTrailingZeroBits >= 0L && maxTrailingZeroBits < 8L, "maxTrailingZeroBits must be between 0 and 7")
    val maxBufferedBits = maxBufferedBytes * 8L

    def loop(
      state: StreamState,
      pending: Option[PendingInput],
    ): ZChannel[Any, Throwable, Chunk[BitVector], Any, Throwable, Chunk[A], Unit] =
      def continue(outcome: BatchOutcome[A]) =
        val emit =
          if outcome.values.isEmpty then ZChannel.unit
          else ZChannel.write(outcome.values)

        outcome.failure match
          case Some(error) => emit *> ZChannel.fail(error)
          case None        =>
            if outcome.stop then emit
            else
              val nextPending = Option.when(outcome.resumeWithoutRead)(outcome.pending)
              emit *> loop(outcome.state, nextPending)

      pending match
        case Some(input) =>
          continue(
            decodeBatch(decoder, once, failOnError, maxBufferedBits, maxOutputBatchValues)(state, input)
          )
        case None        =>
          ZChannel.readWith(
            chunk =>
              continue(
                decodeBatch(decoder, once, failOnError, maxBufferedBits, maxOutputBatchValues)(
                  state,
                  PendingInput(chunk),
                )
              ),
            err => ZChannel.fail(err),
            _ => finish(state, once, failOnError, maxTrailingZeroBits),
          )

    ZPipeline.fromChannel(loop(StreamState.empty, None))

  private def decodeBatch[A](
    decoder: Decoder[A],
    once: Boolean,
    failOnError: Boolean,
    maxBufferedBits: Long,
    maxOutputBatchValues: Int,
  )(
    state: StreamState,
    initialPending: PendingInput,
  ): BatchOutcome[A] =
    var buffer       = state.buffer
    var pending      = initialPending
    val builder      = ChunkBuilder.make[A]()
    var decodedAny   = state.decodedAny
    var valueCount   = 0
    var needsInput   = buffer.isEmpty
    var inputDrained = false
    var stop         = false
    var failure      = Option.empty[CodecError]

    def loadCurrent(): Boolean =
      while pending.current.isEmpty && pending.nextIndex < pending.source.length do
        val index = pending.nextIndex
        pending = pending.copy(current = pending.source(index), nextIndex = index + 1)
      pending.current.nonEmpty

    while valueCount < maxOutputBatchValues && !inputDrained && !stop && failure.isEmpty do
      if needsInput then
        if !loadCurrent() then inputDrained = true
        else
          val capacity = maxBufferedBits - buffer.size
          if capacity <= 0L then failure = Some(bufferLimitError(maxBufferedBits))
          else
            val appendSize = math.min(capacity, pending.current.size)
            buffer = buffer ++ pending.current.take(appendSize)
            pending = pending.copy(current = pending.current.drop(appendSize))
            needsInput = false
      else
        decoder.decode(buffer) match
          case Attempt.Successful(DecodeResult(value, remainder)) =>
            val consumed = remainder.size != buffer.size

            if !consumed && !once then failure = Some(CodecError(Err("decoder did not consume any input")))
            else
              builder += value
              valueCount += 1
              decodedAny = true
              buffer = remainder

              if once then stop = true
              else needsInput = buffer.isEmpty

          case Attempt.Failure(err) =>
            if findInsufficient(err).nonEmpty then needsInput = true
            else if failOnError then failure = Some(CodecError(err))
            else
              buffer = BitVector.empty
              stop = true

    val batchFull         = valueCount >= maxOutputBatchValues
    val resumeWithoutRead =
      failure.isEmpty && !stop && (pending.hasRemaining || (batchFull && buffer.nonEmpty))

    BatchOutcome(
      state = StreamState(buffer, decodedAny),
      pending = pending,
      values = builder.result(),
      resumeWithoutRead = resumeWithoutRead,
      stop = stop,
      failure = failure,
    )

  private def finish[A](
    state: StreamState,
    once: Boolean,
    failOnError: Boolean,
    maxTrailingZeroBits: Long,
  ): ZChannel[Any, Throwable, Chunk[BitVector], Any, Throwable, Chunk[A], Unit] =
    if failOnError && once && !state.decodedAny && state.buffer.isEmpty then
      ZChannel.fail(CodecError(Err("input ended before a value was decoded")))
    else if failOnError && state.buffer.nonEmpty && !isAllowedTrailingPadding(state.buffer, maxTrailingZeroBits) then
      ZChannel.fail(CodecError(Err(s"input ended with ${state.buffer.size} buffered bits before a complete value")))
    else ZChannel.unit

  private def findInsufficient(err: Err): Option[Err.InsufficientBits] =
    err match
      case e: Err.InsufficientBits => Some(e)
      case Err.Composite(errs, _)  =>
        errs
          .collectFirst { case nested: Err.InsufficientBits => nested }
          .orElse(errs.view.flatMap(findInsufficient).headOption)
      case _                       => None

  private def isAllowedTrailingPadding(buffer: BitVector, maxTrailingZeroBits: Long): Boolean =
    buffer.size <= maxTrailingZeroBits && buffer.populationCount == 0L

  private def bufferLimitError(maxBufferedBits: Long): CodecError =
    CodecError(Err(s"decoder buffer exceeds ${maxBufferedBits / 8L} bytes"))
