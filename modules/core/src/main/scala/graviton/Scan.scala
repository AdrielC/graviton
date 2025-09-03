package graviton

import zio.*
import zio.stream.*

/**
 * A stateful transformation of a stream, adapted from fs2.Scan for ZIO streams.
 */
final class Scan[S, -I, +O](val initial: S, val transform: (S, I) => (S, Chunk[O]), val onComplete: S => Chunk[O]):

  /** Transformation function over a chunk. */
  def transformAccumulate(s: S, c: Chunk[I]): (S, Chunk[O]) =
    c.foldLeft(s -> Chunk.empty[O]) { case ((state, acc), i) =>
      val (s2, out) = transform(state, i)
      (s2, acc ++ out)
    }

  /** Convert this scan to a ZPipeline. */
  def toPipeline: ZPipeline[Any, Nothing, I, O] =
    def loop(s: S): ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], Any] =
      ZChannel.readWith(
        (in: Chunk[I]) =>
          val (s2, out) = transformAccumulate(s, in)
          ZChannel.write(out) *> loop(s2),
        (err: Cause[Nothing]) => ZChannel.refailCause(err),
        (_: Any) => ZChannel.write(onComplete(s))
      )
    ZPipeline.fromChannel(loop(initial))

  def map[O2](f: O => O2): Scan[S, I, O2] =
    Scan[S, I, O2](initial)(
      (s: S, i: I) => {
        val (s2, os) = transform(s, i)
        (s2, os.map(f))
      },
      (s: S) => onComplete(s).map(f)
    )

  def contramap[I2](f: I2 => I): Scan[S, I2, O] =
    Scan[S, I2, O](initial)(
      (s: S, i2: I2) => transform(s, f(i2)),
      (s: S) => onComplete(s)
    )

  def dimap[I2, O2](g: I2 => I)(f: O => O2): Scan[S, I2, O2] =
    Scan[S, I2, O2](initial)(
      (s: S, i2: I2) => {
        val (s2, os) = transform(s, g(i2))
        (s2, os.map(f))
      },
      (s: S) => onComplete(s).map(f)
    )

  def andThen[S2, O2](that: Scan[S2, O, O2]): Scan[(S, S2), I, O2] =
    Scan[(S, S2), I, O2]((initial, that.initial))(
      { case ((s, s2), i) =>
        val (sp, os) = transform(s, i)
        val (s2p, out) = that.transformAccumulate(s2, os)
        ((sp, s2p), out)
      },
      { case (s, s2) =>
        val (s2p, out) = that.transformAccumulate(s2, this.onComplete(s))
        out ++ that.onComplete(s2p)
      }
    )

  def imapState[S2](g: S => S2)(f: S2 => S): Scan[S2, I, O] =
    Scan[S2, I, O](g(initial))(
      (s2: S2, i: I) => {
        val (s3, os) = transform(f(s2), i)
        (g(s3), os)
      },
      (s2: S2) => onComplete(f(s2))
    )

  def lens[I2, O2](get: I2 => I, set: (I2, O) => O2): Scan[S, I2, O2] =
    Scan[S, I2, O2](initial)(
      (s: S, i2: I2) => {
        val (s2, os) = transform(s, get(i2))
        (s2, os.map(o => set(i2, o)))
      },
      (_: S) => Chunk.empty
    )

  def first[A]: Scan[S, (I, A), (O, A)] =
    lens(_._1, (t, o) => (o, t._2))

  def second[A]: Scan[S, (A, I), (A, O)] =
    lens(_._2, (t, o) => (t._1, o))

  def semilens[I2, O2](extract: I2 => Either[O2, I], inject: (I2, O) => O2): Scan[S, I2, O2] =
    Scan[S, I2, O2](initial)(
      (s: S, i2: I2) =>
        extract(i2) match
          case Left(o2)  => (s, Chunk.single(o2))
          case Right(i)  =>
            val (s2, os) = transform(s, i)
            (s2, os.map(o => inject(i2, o)))
      ,
      (_: S) => Chunk.empty
    )

  def semipass[I2, O2 >: O](extract: I2 => Either[O2, I]): Scan[S, I2, O2] =
    semilens(extract, (_, o) => o)

  def left[A]: Scan[S, Either[I, A], Either[O, A]] =
    semilens(_.fold(i => Right(i), a => Left(Right(a))), (_, o) => Left(o))

  def right[A]: Scan[S, Either[A, I], Either[A, O]] =
    semilens(_.fold(a => Left(Left(a)), i => Right(i)), (_, o) => Right(o))

  def choice[S2, I2, O2 >: O](that: Scan[S2, I2, O2]): Scan[(S, S2), Either[I, I2], O2] =
    Scan[(S, S2), Either[I, I2], O2]((initial, that.initial))(
      { (state: (S, S2), e: Either[I, I2]) =>
        val (s, s2) = state
        e match
          case Left(i) =>
            val (sp, os) = transform(s, i)
            ((sp, s2), os)
          case Right(i2) =>
            val (s2p, o2s) = that.transform(s2, i2)
            ((s, s2p), o2s)
      },
      { (state: (S, S2)) =>
        val (s, s2) = state
        this.onComplete(s) ++ that.onComplete(s2)
      }
    )

object Scan:
  def apply[S, I, O](initial: S)(transform: (S, I) => (S, Chunk[O]), onComplete: S => Chunk[O]): Scan[S, I, O] =
    new Scan(initial, transform, onComplete)

  def lift[I, O](f: I => O): Scan[Unit, I, O] =
    Scan(())((s, i) => (s, Chunk.single(f(i))), _ => Chunk.empty)

  def identity[I]: Scan[Unit, I, I] = lift[I, I](i => i)
