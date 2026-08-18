package graviton.streams.interop.scodec

import zio.*
import zio.stream.*
import zio.ChunkBuilder

import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult, Decoder, Err}

final case class CodecError(err: Err) extends Exception(err.messageWithContext)

object ZStreamDecoder:

  def once[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    decode(decoder, once = true, failOnError = true)

  def many[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    decode(decoder, once = false, failOnError = true)

  def tryOnce[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    decode(decoder, once = true, failOnError = false)

  def tryMany[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    decode(decoder, once = false, failOnError = false)

  private final case class DecoderState(
    buffer: BitVector,
    awaiting: Option[Err.InsufficientBits],
  )

  private object DecoderState:
    val empty: DecoderState = DecoderState(BitVector.empty, None)

  private final case class StepOutcome[A](values: Chunk[A], stop: Boolean)

  private def decode[A](
    decoder: Decoder[A],
    once: Boolean,
    failOnError: Boolean,
  ): ZPipeline[Any, Throwable, BitVector, A] =
    def loop(
      state: DecoderState
    ): ZChannel[Any, Throwable, Chunk[BitVector], Any, Throwable, Chunk[A], Unit] =
      ZChannel.readWith(
        chunk =>
          processChunk(decoder, once, failOnError)(chunk, state) match
            case Left(err)                   => ZChannel.fail(err)
            case Right((nextState, outcome)) =>
              val emit =
                if outcome.values.isEmpty then ZChannel.unit
                else ZChannel.write(outcome.values)
              if outcome.stop then emit
              else emit *> loop(nextState),
        err => ZChannel.fail(err),
        _ => ZChannel.unit,
      )

    ZPipeline.fromChannel(loop(DecoderState.empty))

  private def processChunk[A](
    decoder: Decoder[A],
    once: Boolean,
    failOnError: Boolean,
  )(
    chunk: Chunk[BitVector],
    state: DecoderState,
  ): Either[CodecError, (DecoderState, StepOutcome[A])] =
    decodeChunk(decoder, once, failOnError)(state, chunk)

  private def decodeChunk[A](
    decoder: Decoder[A],
    once: Boolean,
    failOnError: Boolean,
  )(
    state: DecoderState,
    input: Chunk[BitVector],
  ): Either[CodecError, (DecoderState, StepOutcome[A])] =
    var buffer   = state.buffer
    var awaiting = state.awaiting
    val builder  = ChunkBuilder.make[A]()
    var stop     = false
    var idx      = 0

    while idx < input.length && !stop do
      val bits = input(idx)
      if !bits.isEmpty then
        buffer = buffer ++ bits
        awaiting = None
        var continue = true

        while continue && !stop do
          decoder.decode(buffer) match
            case Attempt.Successful(DecodeResult(value, remainder)) =>
              val consumed = remainder.size != buffer.size

              if !consumed && !once then return Left(CodecError(Err("decoder did not consume any input")))

              builder += value
              buffer = remainder
              awaiting = None

              if once then
                stop = true
                continue = false
              else continue = consumed && buffer.nonEmpty

            case Attempt.Failure(err) =>
              findInsufficient(err) match
                case Some(insufficient) =>
                  awaiting = Some(insufficient)
                  continue = false
                case None               =>
                  if failOnError then return Left(CodecError(err))
                  else
                    buffer = BitVector.empty
                    awaiting = None
                    stop = true
                    continue = false

      idx += 1

    val nextState = DecoderState(buffer, awaiting)
    Right(nextState -> StepOutcome(builder.result(), stop))

  private def findInsufficient(err: Err): Option[Err.InsufficientBits] =
    err match
      case e: Err.InsufficientBits => Some(e)
      case Err.Composite(errs, _)  =>
        errs
          .collectFirst { case nested: Err.InsufficientBits => nested }
          .orElse(errs.view.flatMap(findInsufficient).headOption)
      case _                       => None
