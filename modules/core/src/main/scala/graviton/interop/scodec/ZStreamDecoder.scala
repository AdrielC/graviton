package graviton.interop.scodec

import zio._
import zio.stream._
import zio.ChunkBuilder
import zio.prelude.fx.ZPure
import _root_.scodec.{Attempt, DecodeResult, Decoder, Err}
import _root_.scodec.bits.BitVector

/** A simple error wrapper used when a scodec decoder fails. */
final case class CodecError(err: Err) extends Exception(err.messageWithContext)

/**
 * Utilities for decoding binary data using scodec within ZIO streams.
 *
 * The API mirrors a subset of fs2's `StreamDecoder` helpers and focuses on
 * two decoding modes: [[once]] for decoding a single value and [[many]] for
 * repeatedly decoding values. Each mode has a lenient variant ([[tryOnce]] and
 * [[tryMany]]) that swallows unrecoverable errors and terminates the stream
 * gracefully.
 */
object ZStreamDecoder {

  /** Decode at most one value from the incoming stream, failing on errors. */
  def once[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    decode(decoder, once = true, failOnError = true)

  /** Decode values repeatedly from the incoming stream, failing on errors. */
  def many[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    decode(decoder, once = false, failOnError = true)

  /**
   * Decode at most one value from the incoming stream. Unrecoverable errors are
   * swallowed and terminate the pipeline.
   */
  def tryOnce[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    decode(decoder, once = true, failOnError = false)

  /**
   * Decode values repeatedly from the incoming stream. Unrecoverable errors are
   * swallowed and terminate the pipeline.
   */
  def tryMany[A](decoder: Decoder[A]): ZPipeline[Any, Throwable, BitVector, A] =
    decode(decoder, once = false, failOnError = false)

  private final case class DecoderState(
    buffer: BitVector,
    awaiting: Option[Err.InsufficientBits],
  )

  private object DecoderState {
    val empty: DecoderState = DecoderState(BitVector.empty, None)
  }

  private final case class StepOutcome[A](values: Chunk[A], stop: Boolean)

  private def decode[A](
    decoder: Decoder[A],
    once: Boolean,
    failOnError: Boolean,
  ): ZPipeline[Any, Throwable, BitVector, A] = {
    def loop(
      state: DecoderState
    ): ZChannel[Any, Throwable, Chunk[BitVector], Any, Throwable, Chunk[A], Unit] =
      ZChannel.readWith(
        chunk =>
          processChunk(decoder, once, failOnError)(chunk, state) match {
            case Left(err)                   => ZChannel.fail(err)
            case Right((nextState, outcome)) =>
              val emit =
                if (outcome.values.isEmpty) ZChannel.unit
                else ZChannel.write(outcome.values)
              if (outcome.stop) emit
              else emit *> loop(nextState)
          },
        err => ZChannel.fail(err),
        _ => ZChannel.unit,
      )

    ZPipeline.fromChannel(loop(DecoderState.empty))
  }

  private def processChunk[A](
    decoder: Decoder[A],
    once: Boolean,
    failOnError: Boolean,
  )(
    chunk: Chunk[BitVector],
    state: DecoderState,
  ): Either[CodecError, (DecoderState, StepOutcome[A])] = {
    val step =
      ZPure
        .modify[DecoderState, DecoderState, Either[CodecError, StepOutcome[A]]] { st =>
          decodeChunk(decoder, once, failOnError)(st, chunk) match {
            case Left(err)              => (Left(err), st)
            case Right((next, outcome)) => (Right(outcome), next)
          }
        }
        .flatMap {
          case Left(err)      => ZPure.fail(err)
          case Right(outcome) => ZPure.succeed(outcome)
        }

    val (_, either) = step.runAll(state)
    either match {
      case Left(err)              => Left(err)
      case Right((next, outcome)) => Right(next -> outcome)
    }
  }

  private def decodeChunk[A](
    decoder: Decoder[A],
    once: Boolean,
    failOnError: Boolean,
  )(
    state: DecoderState,
    input: Chunk[BitVector],
  ): Either[CodecError, (DecoderState, StepOutcome[A])] = {
    var buffer   = state.buffer
    var awaiting = state.awaiting
    val builder  = ChunkBuilder.make[A]()
    var stop     = false
    var idx      = 0

    while (idx < input.length && !stop) {
      val bits = input(idx)
      if (!bits.isEmpty) {
        buffer = buffer ++ bits
        awaiting = None
        var continue = true

        while (continue && !stop) {
          decoder.decode(buffer) match {
            case Attempt.Successful(DecodeResult(value, remainder)) =>
              val consumed = remainder.size != buffer.size

              if (!consumed && !once) {
                return Left(CodecError(Err("decoder did not consume any input")))
              }

              builder += value
              buffer = remainder
              awaiting = None

              if (once) {
                stop = true
                continue = false
              } else {
                continue = consumed && buffer.nonEmpty
              }
            case Attempt.Failure(err)                               =>
              findInsufficient(err) match {
                case Some(insufficient) =>
                  awaiting = Some(insufficient)
                  continue = false
                case None               =>
                  if (failOnError) return Left(CodecError(err))
                  else {
                    buffer = BitVector.empty
                    awaiting = None
                    stop = true
                    continue = false
                  }
              }
          }
        }
      }

      idx += 1
    }

    val nextState = DecoderState(buffer, awaiting)
    Right(nextState -> StepOutcome(builder.result(), stop))
  }

  private def findInsufficient(err: Err): Option[Err.InsufficientBits] =
    err match {
      case e: Err.InsufficientBits => Some(e)
      case Err.Composite(errs, _)  =>
        errs
          .collectFirst { case nested: Err.InsufficientBits => nested }
          .orElse(errs.view.flatMap(findInsufficient).headOption)
      case _                       => None
    }
}
