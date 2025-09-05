package torrent.bytes

import scodec.bits.*

import zio.*
import zio.prelude.EState
import zio.schema.codec.BinaryCodec.BinaryDecoder
import zio.schema.codec.DecodeError
import zio.stream.*

/**
 * A modern StreamDecoder that consumes BitVectors and emits Chunk[A], using an
 * EState-powered decode engine and policy-driven decode behavior.
 */
final case class ZStreamDecoder[+A](
  step:   EState[BitVector, Throwable, A],
  policy: ZStreamDecoder.Policy
) extends BinaryDecoder[A] { self =>

  override def decode(whole: Chunk[Byte]): Either[DecodeError, A] =
    step
      .provideState(BitVector(whole.toArray))
      .runEither
      .left
      .map: e =>
        DecodeError.ReadError(Cause.fail(e),
                              Option(s"${e.getClass.getName}: ${e.getMessage}")
                                .getOrElse(s"Read failed with ${e.getClass.getName}")
        )

  override def streamDecoder: ZPipeline[Any, DecodeError, Byte, A] =
    toPipeline.mapErrorCause: e =>
      Cause.fail(DecodeError.ReadError(Cause.fail(e), e.prettyPrint))

  import ZStreamDecoder.*

  def toByteChannel: ZChannel[Any, Throwable, Chunk[Byte], Any, Throwable, Chunk[A], BitVector] =
    toChannel.contramapIn[Chunk[Byte]](c => BitVector(c.toArray[Byte]))

  /** Converts this decoder into a ZChannel that can be run in a ZPipeline. */
  def toChannel: ZChannel[Any, Throwable, BitVector, Any, Throwable, Chunk[A], BitVector] = {
    def loop(carry: BitVector): ZChannel[Any, Throwable, BitVector, Any, Throwable, Chunk[A], BitVector] =
      ZChannel.readWithCause(
        (input: BitVector) => {
          val buffer = carry ++ input
          decodeBuffer(buffer)
        },
        cause => ZChannel.failCause(cause),
        _ => ZChannel.succeed(carry)
      )

    def decodeBuffer(buffer: BitVector): ZChannel[Any, Throwable, BitVector, Any, Throwable, Chunk[A], BitVector] =
      policy match {
        case Policy.Once | Policy.TryOnce =>
          step.getState.provideState(buffer).runEither match
            case Left(_) if policy.isTry   =>
              ZChannel.succeed(buffer)
            case Left(err)                 =>
              ZChannel.succeed(buffer) *> ZChannel.fail(err)
            case Right((remainder, value)) =>
              ZChannel.write(Chunk.single(value)) *>
                loop(remainder)

        case Policy.Many | Policy.TryMany =>

          @scala.annotation.tailrec
          def decodeMany(bits: BitVector,
                         acc:  ChunkBuilder[A]
          ): ZChannel[Any, Throwable, BitVector, Any, Throwable, Chunk[A], BitVector] =
            step.getState.provideState(bits).runEither match {
              case Right(rem, value) =>
                decodeMany(rem, acc += value)
              case Left(err)         =>
                if (policy.isTry) {
                  // Soft stop - return what we have so far
                  if (acc.result().isEmpty) {
                    loop(bits) // no new data decoded, wait for more input
                  } else {
                    ZChannel.succeed(acc.result()) *> loop(bits)
                  }
                } else {
                  // Hard stop - propagate error
                  ZChannel.fail(err)
                }
            }

          decodeMany(buffer, ChunkBuilder.make[A]())
      }

    loop(BitVector.empty)
  }

  def toSink: ZSink[Any, Throwable, Byte, A, BitVector] =
    toByteChannel.toSink[Byte, A]

  /** Converts this decoder into a ZPipeline. */
  def toPipeline: ZPipeline[Any, Throwable, Byte, A] =
    toByteChannel.toPipeline

  /** Maps the output value of this decoder. */
  def map[B](f: A => B): ZStreamDecoder[B] =
    ZStreamDecoder(step.map(f), policy)

  /** FlatMaps the output value into a new decoder. */
  def flatMap[B](f: A => ZStreamDecoder[B]): ZStreamDecoder[B] =
    ZStreamDecoder(
      step.flatMap(a => f(a).step),
      policy
    )

  /** Concatenates another decoder after this one. */
  def ++[A2 >: A](that: => ZStreamDecoder[A2]): ZStreamDecoder[A2] =
    ZStreamDecoder(
      step.flatMap(_ => that.step),
      policy
    )

  /** Handles decoding errors. */
  def handleErrorWith[A2 >: A](f: Throwable => ZStreamDecoder[A2]): ZStreamDecoder[A2] =
    ZStreamDecoder(
      step.catchAll(e => f(e).step),
      policy
    )
}

object ZStreamDecoder {

  /** Decoding policy controlling behavior after each decode attempt. */
  enum Policy {
    case Once, Many, TryOnce, TryMany

    def isTry: Boolean = this match
      case TryOnce | TryMany => true
      case _                 => false
  }

  /** Runs the given decoder inside an isolated slice of exactly `bits` bits. */
  def isolate[A](bits: Long)(decoder: ZStreamDecoder[A]): ZStreamDecoder[A] =
    ZStreamDecoder(
      EState.get[BitVector].flatMap { carry =>
        if (carry.size < bits)
          EState.fail(new RuntimeException(s"Insufficient bits: needed $bits, but got ${carry.size}"))
        else {
          val (target, remainder) = carry.splitAt(bits)
          decoder.step.provideState(target).mapState(_ => remainder)
        }
      },
      decoder.policy // keep same policy for inner decoder
    )

  /** Creates a decoder that decodes exactly one A and stops. */
  def once[A](step: EState[BitVector, Throwable, A]): ZStreamDecoder[A] =
    ZStreamDecoder(step, Policy.Once)

  /** Creates a decoder that decodes as many A as possible. */
  def many[A](step: EState[BitVector, Throwable, A]): ZStreamDecoder[A] =
    ZStreamDecoder(step, Policy.Many)

  /** Creates a decoder that tries decoding one A, soft-failing on errors. */
  def tryOnce[A](step: EState[BitVector, Throwable, A]): ZStreamDecoder[A] =
    ZStreamDecoder(step, Policy.TryOnce)

  /**
   * Creates a decoder that tries decoding as many A as possible, soft-failing.
   */
  def tryMany[A](step: EState[BitVector, Throwable, A]): ZStreamDecoder[A] =
    ZStreamDecoder(step, Policy.TryMany)

  /** Creates a decoder that emits a constant value without consuming input. */
  def emit[A](a: A): ZStreamDecoder[A] =
    ZStreamDecoder(EState.succeed(a), Policy.Once)

  /** A decoder that emits no outputs. */
  val empty: ZStreamDecoder[Nothing] =
    ZStreamDecoder(EState.fail(new NoSuchElementException("ZStreamDecoder.empty")), Policy.Once)

  /** A decoder that always fails with the given error. */
  def fail(cause: Throwable): ZStreamDecoder[Nothing] =
    ZStreamDecoder(EState.fail(cause), Policy.Once)

  enum Error extends Throwable:
    case InsufficientBits
}
