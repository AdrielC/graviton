package graviton

import zio.*
import zio.stream.*
import zio.ChunkBuilder

/**
 * A stateful stream transformer whose state is a product (typically a tuple).
 * Stateless scans use `EmptyTuple` and therefore do not alter the state type
 * when composed.
 */
trait Scan[-I, +O]:
  type State <: Tuple

  def initial: State
  def step(state: State, in: I): (State, Chunk[O])
  def done(state: State): Chunk[O]

  final def toPipeline: ZPipeline[Any, Nothing, I, O] =
    def loop(state: State): ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], Any] =
      ZChannel.readWith(
        in =>
          val (s, outs) = in.foldLeft((state, Chunk.empty[O])) { (acc, i) =>
            val (st, ch) = step(acc._1, i)
            (st, acc._2 ++ ch)
          }
          ZChannel.write(outs) *> loop(s),
        err => ZChannel.refailCause(err),
        _   => ZChannel.write(done(state))
      )
    ZPipeline.fromChannel(loop(initial))

  final def toSink: ZSink[Any, Nothing, I, Nothing, Chunk[O]] =
    toPipeline >>> ZSink.collectAll[O]

  def map[O2](f: O => O2): Scan.Aux[I, O2, State] =
    Scan(initial)((s: State, i: I) =>
      val (s2, o) = step(s, i)
      (s2, o.map(f))
    )(s => done(s).map(f))

  def contramap[I2](f: I2 => I): Scan.Aux[I2, O, State] =
    Scan(initial)((s: State, i2: I2) => step(s, f(i2): I))(done)

  def dimap[I2, O2](g: I2 => I)(f: O => O2): Scan.Aux[I2, O2, State] =
    Scan(initial)((s: State, i2: I2) =>
      val (s2, o) = step(s, g(i2): I)
      (s2, o.map(f))
    )(s => done(s).map(f))

  def andThen[O2, S2 <: Tuple](that: Scan.Aux[O, O2, S2]): Scan.Aux[I, O2, Tuple.Concat[State, S2]] =
    val sizeA = initial.productArity
    Scan(initial ++ that.initial)((st: Tuple, i: I) =>
      val s1 = st.take(sizeA).asInstanceOf[State]
      val s2 = st.drop(sizeA).asInstanceOf[S2]
      val (s1b, o1) = step(s1, i)
      var s2b = s2
      val builder = ChunkBuilder.make[O2]()
      o1.foreach { o =>
        val (s2n, o2) = that.step(s2b, o)
        s2b = s2n
        builder ++= o2
      }
      ((s1b ++ s2b).asInstanceOf[Tuple.Concat[State, S2]], builder.result())
    ) { (st: Tuple) =>
      val s1 = st.take(sizeA).asInstanceOf[State]
      val s2 = st.drop(sizeA).asInstanceOf[S2]
      val interim = done(s1)
      var s2b = s2
      val builder = ChunkBuilder.make[O2]()
      interim.foreach { o =>
        val (s2n, o2) = that.step(s2b, o)
        s2b = s2n
        builder ++= o2
      }
      builder.result() ++ that.done(s2b)
    }

object Scan:
  type Aux[-I, +O, S <: Tuple] = Scan[I, O] { type State = S }

  inline def apply[I, O, S <: Tuple](init: S)(
      stepFn: (S, I) => (S, Chunk[O])
    )(doneFn: S => Chunk[O]): Aux[I, O, S] =
    new Scan[I, O]:
      type State = S
      val initial = init
      def step(state: S, in: I) = stepFn(state, in)
      def done(state: S) = doneFn(state)

  inline def lift[I, O](f: I => O): Aux[I, O, EmptyTuple] =
    apply(EmptyTuple)((s: EmptyTuple, i: I) => (s, Chunk.single(f(i))))(_ => Chunk.empty)

  inline def identity[I]: Aux[I, I, EmptyTuple] = lift((i: I) => i)
