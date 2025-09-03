package graviton

import zio.*
import zio.stream.*
import zio.ChunkBuilder
import scala.compiletime.{erasedValue, summonFrom}

/**
  * A stateful stream transformer whose state is represented as a Tuple.
  * Stateless scans use `EmptyTuple` and therefore do not alter the state type
  * when composed. Composition operators are `transparent inline` to encourage
  * compile-time optimisation and eliminate intermediate allocations.
  */
sealed trait Scan[-I, +O]:
  type State <: Tuple

  def initial: State
  def step(state: State, in: I): (State, Chunk[O])
  def done(state: State): Chunk[O]

  transparent inline def toPipeline: ZPipeline[Any, Nothing, I, O] =
    def loop(state: State): ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], Any] =
      ZChannel.readWith(
        in =>
          val (s, outs) = in.foldLeft((state, Chunk.empty[O])) { (acc, i) =>
            val (st, ch) = step(acc._1, i)
            (st, acc._2 ++ ch)
          }
          ZChannel.write(outs) *> loop(s),
        err => ZChannel.refailCause(err),
        _ => ZChannel.write(done(state))
      )
    ZPipeline.fromChannel(loop(initial))

  transparent inline def toSink: ZSink[Any, Nothing, I, Nothing, Chunk[O]] =
    toPipeline >>> ZSink.collectAll[O]

  transparent inline def map[O2](inline f: O => O2): Scan.Aux[I, O2, State] =
    Scan.statefulTuple(initial)((s: State, i: I) =>
      val (s2, o) = step(s, i)
      (s2, o.map(f))
    )(s => done(s).map(f))

  transparent inline def contramap[I2](inline f: I2 => I): Scan.Aux[I2, O, State] =
    Scan.statefulTuple(initial)((s: State, i2: I2) => step(s, f(i2): I))(done)

  transparent inline def dimap[I2, O2](inline g: I2 => I)(inline f: O => O2): Scan.Aux[I2, O2, State] =
    Scan.statefulTuple(initial)((s: State, i2: I2) =>
      val (s2, o) = step(s, g(i2): I)
      (s2, o.map(f))
    )(s => done(s).map(f))

  transparent inline def andThen[O2, S2 <: Tuple](inline that: Scan.Aux[O, O2, S2]): Scan.Aux[I, O2, Tuple.Concat[State, S2]] =
    val sizeA = initial.productArity
    Scan.statefulTuple(initial ++ that.initial)((st: Tuple, i: I) =>
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

  transparent inline def flatMap[O2, S2 <: Tuple](inline that: Scan.Aux[O, O2, S2]): Scan.Aux[I, O2, Tuple.Concat[State, S2]] =
    andThen(that)

object Scan:
  type Aux[-I, +O, S <: Tuple] = Scan[I, O] { type State = S }

  // ---------------------- Stateless implementations ----------------------

  sealed trait Stateless[-I, +O] extends Scan[I, O]:
    final type State = EmptyTuple
    final val initial: EmptyTuple = EmptyTuple

  private[Scan] final class StatelessChain[I, O](val fs: Chunk[Any => Any]) extends Stateless[I, O]:
    def step(state: EmptyTuple, in: I) =
      var res: Any = in
      var i = 0
      while i < fs.length do
        res = fs(i)(res)
        i += 1
      (state, Chunk.single(res.asInstanceOf[O]))
    def done(state: EmptyTuple) = Chunk.empty
  end StatelessChain

  sealed trait StatelessChunk[-I, +O] extends Scan[I, O]:
    final type State = EmptyTuple
    final val initial: EmptyTuple = EmptyTuple

  transparent inline def stateless[I, O](inline f: I => O): Stateless[I, O] =
    new StatelessChain[I, O](Chunk.single(f.asInstanceOf[Any => Any]))

  transparent inline def statelessChunk[I, O](inline f: I => Chunk[O]): StatelessChunk[I, O] =
    new StatelessChunk[I, O]:
      def step(state: EmptyTuple, in: I) = (state, f(in))
      def done(state: EmptyTuple) = Chunk.empty

  // ---------------------- Stateful helpers ----------------------

  sealed trait Stateful[-I, +O, S <: Tuple] extends Scan[I, O]:
    final type State = S
    val initial: S

  transparent inline def stateful[I, O, S](init: S)(inline stepFn: (S, I) => (S, Chunk[O]))(inline doneFn: S => Chunk[O]): Stateful[I, O, S *: EmptyTuple] =
    new Stateful[I, O, S *: EmptyTuple]:
      val initial = init *: EmptyTuple
      def step(state: S *: EmptyTuple, in: I) =
        val (s, o) = stepFn(state.head, in)
        (s *: EmptyTuple, o)
      def done(state: S *: EmptyTuple) = doneFn(state.head)

  transparent inline def statefulTuple[I, O, S <: Tuple](init: S)(inline stepFn: (S, I) => (S, Chunk[O]))(inline doneFn: S => Chunk[O]): Stateful[I, O, S] =
    new Stateful[I, O, S]:
      val initial = init
      def step(state: S, in: I) = stepFn(state, in)
      def done(state: S) = doneFn(state)

  transparent inline def apply[I, O, S](init: S)(inline stepFn: (S, I) => (S, Chunk[O]))(inline doneFn: S => Chunk[O]): Stateful[I, O, S *: EmptyTuple] =
    stateful(init)(stepFn)(doneFn)

  // identity
  private object Identity extends Stateless[Any, Any]:
    def step(state: EmptyTuple, in: Any) = (state, Chunk.single(in))
    def done(state: EmptyTuple) = Chunk.empty

  transparent inline def identity[I]: Aux[I, I, EmptyTuple] =
    Identity.asInstanceOf[Aux[I, I, EmptyTuple]]

  transparent inline def lift[I, O](inline f: I => O): Aux[I, O, EmptyTuple] =
    stateless(f)
end Scan
