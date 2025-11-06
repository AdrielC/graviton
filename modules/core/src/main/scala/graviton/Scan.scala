package graviton

import zio.*
import zio.stream.*
import zio.ChunkBuilder
import zio.stream.Take
import zio.prelude.fx.ZPure
// import scala.compiletime.{erasedValue, summonFrom}
// removed iron refined constraints from this file for simplicity
import cats.Eval
import cats.data.Ior
import graviton.core.model.Block
import graviton.domain.HashBytes

/**
 * A stateful stream transformer whose state is represented as a Tuple.
 * Stateless scans use `EmptyTuple` and therefore do not alter the state type
 * when composed. Composition operators are `transparent inline` to encourage
 * compile-time optimisation and eliminate intermediate allocations.
 */
sealed trait Scan[-I, +O]:

  type S <: Matchable

  type State = Scan.ToState[S]

  type Size = scala.Tuple.Size[State]

  inline def size: Size = scala.compiletime.summonInline[Size]

  private[graviton] def _initial: Eval[S]

  final def initialState: Eval[State] = _initial.map(Scan.toState(_))

  final def initial: State = initialState.value

  type FromState[A <: Tuple] = A match
    case EmptyTuple      => Unit
    case n *: EmptyTuple => Tuple.Head[A]
    case _               => Tuple.Take[A, Size]

  def fromState[A <: State & Matchable](a: A): FromState[A] = a match
    case _: EmptyTuple.type => ().asInstanceOf[FromState[A]]
    case h *: EmptyTuple    => h.asInstanceOf[FromState[A]]
    case t                  =>
      t.productIterator
        .take(
          scala.compiletime
            .constValueOpt[Size]
            .getOrElse(initial.productArity.asInstanceOf[Size])
        )
        .foldRight[Tuple](EmptyTuple)((o, e) => o *: e)
        .asInstanceOf[FromState[A]]

  def step(state: State, in: I): (State, Chunk[O])
  def done(state: State): Chunk[O]

object Scan:

  type ToState[A] <: Tuple = A match
    case EmptyTuple => EmptyTuple
    case Unit       => EmptyTuple
    case x *: xs    => x *: ToState[xs]

  def toState[A <: Matchable](a: A): ToState[A] =
    a match
      case _: EmptyTuple.type => EmptyTuple.asInstanceOf[ToState[A]]
      case _: Unit            => EmptyTuple.asInstanceOf[ToState[A]]
      case _: (x *: xs)       => (a.asInstanceOf[x *: xs].head *: toState(a.asInstanceOf[x *: xs].tail)).asInstanceOf[ToState[A]]
      case _                  => Tuple1(a).asInstanceOf[ToState[A]]

  type Aux[-I, +O, St <: Matchable] = Scan[I, O] { type S = St }

  // ---------------------- core operations ----------------------

  extension [I, O, S <: Matchable](self: Scan.Aux[I, O, S])
    transparent inline def toChannel: ZChannel[Any, Nothing, Chunk[
      I
    ], Any, Nothing, Take[Nothing, O], self.State] =
      def loop(
        state: self.State
      ): ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Take[Nothing, O], self.State] =
        ZChannel.readWith(
          in =>
            val (s, outs) = in.foldLeft((state, Chunk.empty[O])) { (acc, i) =>
              val (st, ch) = self.step(acc._1, i)
              (st, acc._2 ++ ch)
            }
            ZChannel.write(Take.chunk(outs)) *> loop(s)
          ,
          err => ZChannel.refailCause(err),
          _ =>
            ZChannel.write(Take.chunk(self.done(state))) *>
              ZChannel.write(Take.end) *>
              ZChannel.succeed(state),
        )
      loop(self.initial)

    transparent inline def toPipeline: ZPipeline[Any, Nothing, I, O] =
      ZPipeline.fromChannel(
        toChannel.mapOutZIO(
          _.fold(
            ZIO.succeed(Chunk.empty[O]),
            cause => ZIO.die(cause.squash),
            chunk => ZIO.succeed(chunk),
          )
        )
      )

    transparent inline def toSink: ZSink[Any, Nothing, I, Nothing, Chunk[O]] =
      toPipeline >>> ZSink.collectAll[O]

    def map[O2](f: O => O2): Scan.Aux[I, O2, S] =
      statefulTuple(self._initial)((s: S, i: I) =>
        val (s2, o) = self.step(toState(s), i)
        ((s2).asInstanceOf[S], o.map(f))
      )(s => self.done(toState(s)).map(f))

    def contramap[I2](
      f: I2 => I
    ): Scan.Aux[I2, O, S] =
      statefulTuple(self._initial)((st: S, i2: I2) =>
        val (s2, o) = self.step(toState(st), f(i2): I)
        ((s2).asInstanceOf[S], o)
      )(s => self.done(toState(s)))

    def dimap[I2, O2](g: I2 => I)(
      f: O => O2
    ): Scan.Aux[I2, O2, S] =
      statefulTuple(self._initial)((s: S, i2: I2) =>
        val (s2, o) = self.step(toState(s), g(i2): I)
        ((s2).asInstanceOf[S], o.map(f))
      )(s => self.done(toState(s)).map(f))

    def andThen[O2, S2 <: Matchable](
      that: Scan.Aux[O, O2, S2]
    ): Scan.Aux[I, O2, Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]] =
      type SS = Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]
      val sizeA = self.initial.productArity.asInstanceOf[self.Size]
      statefulTuple(self.initialState.flatMap(s => that.initialState.map(t => s ++ t)))((st: SS, i: I) =>
        val s1        = st.take(sizeA).asInstanceOf[self.State]
        val s2        = st.drop(sizeA).asInstanceOf[that.State]
        val (s1b, o1) = self.step(s1.asInstanceOf[self.State], i)
        var s2b       = toState(s2).asInstanceOf[that.State]
        val builder   = ChunkBuilder.make[O2]()
        o1.foreach { o =>
          val (s2n, o2) = that.step(s2b, o)
          s2b = s2n
          builder ++= o2
        }
        (toState(s1b ++ s2b).asInstanceOf[SS], builder.result())
      ) { (st: Tuple) =>
        val s1      = st.take(sizeA).asInstanceOf[self.State]
        val s2      = st.drop(sizeA).asInstanceOf[that.State]
        val interim = self.done(s1)
        var s2b     = s2
        val builder = ChunkBuilder.make[O2]()
        interim.foreach { o =>
          val (s2n, o2) = that.step(s2b, o)
          s2b = s2n
          builder ++= o2
        }
        builder.result() ++ that.done(s2b)
      }

    transparent inline def flatMap[O2, S2 <: Matchable](
      that: O => Scan.Aux[O, O2, S2]
    ): Scan.Aux[I, O2, Tuple.Concat[self.State, Tuple1[Option[ToState[S2]]]]] =
      Scan.statefulTuple[I, O2, Tuple.Concat[self.State, Tuple1[Option[ToState[S2]]]]](
        self.initial ++ (Tuple1(Option.empty[ToState[S2]]): Tuple1[Option[ToState[S2]]])
      )((s, i: I) =>
        val (s2: self.State, os: Chunk[O]) = self.step(s.drop(1).asInstanceOf[self.State], i)
        os.headOption match
          case None    =>
            ((s2.drop(1).asInstanceOf[self.State] ++ (Tuple1(Option.empty[ToState[S2]]): Tuple1[Option[ToState[S2]]]), Chunk.empty[O2]))
          case Some(o) =>
            val newSc: Scan.Aux[O, O2, S2] = that(o)
            val newState: ToState[S2]      = newSc.initial
            val (newState2, o3)            = newSc.step(newState, o)

            def loop(
              acc: (Option[(Scan.Aux[O, O2, S2], ToState[S2])], Chunk[O2]),
              o: O,
            ): (Option[(Scan.Aux[O, O2, S2], ToState[S2])], Chunk[O2]) =
              acc match
                case (None, o2)                         =>
                  val newSc: Scan.Aux[O, O2, S2] = that(o)
                  val newState: ToState[S2]      = newSc.initial
                  val (newState2, o3)            = newSc.step(newState, o)
                  (Some((newSc, newState2)), o2 ++ o3)
                case (Some((sc, state)), os: Chunk[O2]) =>
                  val (state2, o2) = sc.step(state, o)
                  (Some((sc, state2)), os ++ o2)

            val (newState3: Option[(Scan.Aux[O, O2, S2], ToState[S2])], o4: Chunk[O2]) =
              os.tail.foldLeft((Option.empty[(Scan.Aux[O, O2, S2], ToState[S2])], Chunk.empty[O2]))(loop)

            (
              toState(s2.drop(1).asInstanceOf[self.State]) ++
                Tuple1(Some(newState3.map(_._2).getOrElse(newSc.initial))),
              o4,
            )
              .asInstanceOf[(Tuple.Concat[self.State, (Tuple1[Option[ToState[S2]]])], zio.Chunk[O2])]
      )((s) =>
        self
          .done(s.drop(1).asInstanceOf[self.State])
          .foldLeft[
            (Option[(Scan.Aux[O, O2, S2], ToState[S2])], Chunk[O2])
          ]((Option.empty[(Scan.Aux[O, O2, S2], ToState[S2])], Chunk.empty[O2])) { (acc, o) =>
            val (sc, s2) = acc
            sc match
              case None              =>
                val newSc: Scan.Aux[O, O2, S2] = that(o)
                val newState: ToState[S2]      = newSc.initial
                val (newState2, o3)            = newSc.step(newState, o)
                (Some((newSc, newState2)), o3)
              case Some((sc, state)) =>
                val (state2, o2) = sc.step(state, o)
                (Some((sc, state2)), o2)
          }
          ._2
      )

    transparent inline def zip[O2, S2 <: Matchable](
      that: Scan.Aux[I, O2, S2]
    ): Scan.Aux[I, (O, O2), Tuple.Concat[self.State, ToState[S2]]] =

      type CombinedState = Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]

      val combinedState: CombinedState =
        self.initial ++ that.initial

      val sizeA: Tuple.Size[CombinedState] = scala.compiletime
        .constValueOpt[Tuple.Size[CombinedState]]
        .getOrElse(combinedState.productArity.asInstanceOf[Tuple.Size[CombinedState]])

      statefulTuple(combinedState)((st: CombinedState, i: I) =>
        val (s1, s2)  = st.splitAt(sizeA)
        val (s1b, o1) = self.step(s1.asInstanceOf[self.State], i)
        val (s2b, o2) = that.step(s2.asInstanceOf[that.State], i)
        ((s1b ++ s2b), o1.zip(o2))
      ) { st =>
        val (s1, s2) = st.splitAt(sizeA)
        self
          .done(toState(s1.asInstanceOf[self.S]))
          .zip(that.done(toState(s2.asInstanceOf[that.S])))
      }

    transparent inline def runAll(
      inputs: Iterable[I]
    ): (self.State, Chunk[O]) =
      var state   = self.initial
      val builder = ChunkBuilder.make[O]()
      val it      = inputs.iterator
      while it.hasNext do
        val (s, out) = self.step(state, it.next())
        state = s
        builder ++= out
      builder ++= self.done(state)
      (state, builder.result())

    transparent inline def runZPure(
      inputs: Iterable[I]
    ): ZPure[Nothing, S, self.State, Any, Nothing, Chunk[O]] =
      ZPure.modify { (st: S) =>
        var state   = toState(st)
        val builder = ChunkBuilder.make[O]()
        val it      = inputs.iterator
        while it.hasNext do
          val (s, out) = self.step(state, it.next())
          state = s
          builder ++= out
        builder ++= self.done(state)
        (builder.result(), state)
      }

  // ---------------------- Stateless implementations ----------------------

  sealed trait Stateless[-I, +O](f: I => Chunk[O]) extends Scan[I, O]:
    final type S = EmptyTuple
    override val _initial: Eval[S]   = Eval.now(EmptyTuple)
    def done(state: State): Chunk[O] = Chunk.empty

    def step(state: State, in: I) = (state, f(in))

  private[Scan] case class StatelessChain[I, O](val fs: Chunk[Any => Any])
      extends Stateless[I, O]({ (in: I) =>
        if fs.isEmpty
        then Chunk.single(in.asInstanceOf[O])
        else
          var res: Any = in
          var i        = 0
          while i < fs.length do
            res = fs(i)(res)
            i += 1
          end while
          Chunk.single(res.asInstanceOf[O])
      })

  transparent inline def stateless1[I, O](inline f: I => O): Stateless[I, O] =
    StatelessChain[I, O](Chunk.single(f.asInstanceOf[Any => Any]))

  def stateless[I, O](
    f: I => Chunk[O]
  ): Stateless[I, O] =
    new Stateless[I, O](f) {}

  // ---------------------- Stateful helpers ----------------------

  sealed trait Stateful[St <: Matchable, -I, +O] extends Scan[I, O]:
    override final type S = St

  final case class DefaultStateful[I, O, St <: Matchable](_initial: Eval[St])(
    stepFn: (St, I) => (St, Chunk[O])
  )(onDone: St => Chunk[O])
      extends Stateful[St, I, O]:

    def step(state: State, in: I): (State, Chunk[O]) =
      val st     = fromState(state).asInstanceOf[St]
      val (s, o) = stepFn(st, in)
      (toState(s), o)

    def done(state: State): Chunk[O] =
      val st = fromState(state).asInstanceOf[St]
      onDone(st)

  end DefaultStateful

  inline def stateful[I, O, St <: Matchable](_init: => St)(
    stepFn: (St, I) => (St, Chunk[O])
  )(doneFn: St => Chunk[O]): Stateful[St, I, O] =
    DefaultStateful(Eval.now(_init))(stepFn)(doneFn)

  def statefulTuple[I, O, St <: Matchable](_init: => St)(
    stepFn: (St, I) => (St, Chunk[O])
  )(doneFn: St => Chunk[O]): Stateful[St, I, O] =
    statefulTuple(Eval.later(_init))((s: St, i: I) => stepFn(s, i))(doneFn)

  inline def statefulTuple[I, O, St <: Matchable](_init: Eval[St])(
    stepFn: (St, I) => (St, Chunk[O])
  )(doneFn: St => Chunk[O]): Stateful[St, I, O] =
    stateful(_init.value) { (a: St, b: I) =>
      val (s, o) = stepFn(a, b)
      (s, o)
    }(s => doneFn(s))

  transparent inline def apply[I, O, SS <: Matchable](init: => SS)(
    inline stepFn: (SS, I) => (SS, Chunk[O])
  )(inline doneFn: SS => Chunk[O]): Stateful[SS, I, O] =
    stateful(init)((s: SS, i: I) => stepFn(s, i))(s => doneFn(s))

  // identity
  private[graviton] class Identity[A] extends StatelessChain[A, A](Chunk.empty)

  transparent inline def identity[I]: Aux[I, I, EmptyTuple] =
    new Identity[I]

  transparent inline def lift[I, O](inline f: I => O): Aux[I, O, EmptyTuple] =
    stateless1(f)

  // ---------------------- Built-in scans ----------------------

  /** Counts the number of elements in the incoming stream. */
  val count: Aux[Any, Long, Long] =
    stateful[Any, Long, Long](0L)((s, _) => (s + 1L, Chunk.empty))(s => Chunk.single(s))

  type HashState = java.security.MessageDigest | io.github.rctcwyvrn.blake3.Blake3

  extension [A <: HashState & Matchable](self: A)
    def update(b: Chunk[Byte]): A =
      self match
        case md: java.security.MessageDigest       => md.update(b.toArray); self
        case bl: io.github.rctcwyvrn.blake3.Blake3 => bl.update(b.toArray); self

    def digest: HashBytes =
      self match
        case md: java.security.MessageDigest       => HashBytes.applyUnsafe(Chunk.fromArray(md.digest()))
        case bl: io.github.rctcwyvrn.blake3.Blake3 => HashBytes.applyUnsafe(Chunk.fromArray(bl.digest()))

  /** Computes a hash of all bytes using the provided algorithm. */
  def hash(algo: graviton.HashAlgorithm): Aux[Block, Hash, HashState] =
    statefulTuple(
      algo match
        case HashAlgorithm.SHA256 => java.security.MessageDigest.getInstance("SHA-256")
        case HashAlgorithm.SHA512 => java.security.MessageDigest.getInstance("SHA-512")
        case HashAlgorithm.Blake3 => io.github.rctcwyvrn.blake3.Blake3.newInstance()
    ) { (m: HashState, b: Block) =>
      (m.update(b.bytes), Chunk.empty[Hash])
    }((m: HashState) => Chunk.single(Hash(m.digest, algo)))

  /** Convenience scan that produces both hash and count in one pass. */
  def hashAndCount(
    algo: graviton.HashAlgorithm
  ): Aux[Block, Ior[Hash, Long], (HashState, Long)] =
    statefulTuple(
      (
        algo match
          case HashAlgorithm.SHA256 => java.security.MessageDigest.getInstance("SHA-256")
          case HashAlgorithm.SHA512 => java.security.MessageDigest.getInstance("SHA-512")
          case HashAlgorithm.Blake3 => io.github.rctcwyvrn.blake3.Blake3.newInstance()
        ,
        0L,
      )
    ) { (m: (HashState, Long), b: Block) =>
      (m._1.update(b), m._2 + b.size.toLong) -> Chunk.single(Ior.right(m._2 + b.size.toLong))
    } { (m: (HashState, Long)) =>
      Chunk.single(Ior.both(Hash(m._1.digest, algo), m._2))
    }

  // --------- chunking ---------

  final case class ChunkState[I](buffer: Chunk[I], cost: Int)

  def chunkBy[I](
    costFn: I => Int,
    threshold: Int,
  ): Aux[I, Chunk[I], ChunkState[I]] =
    stateful(ChunkState(Chunk.empty[I], 0)) { (st: ChunkState[I], i: I) =>
      val buf1  = st.buffer :+ i
      val cost1 = st.cost + costFn(i)
      if cost1 >= threshold then (ChunkState(Chunk.empty, 0), Chunk.single(buf1))
      else (ChunkState(buf1, cost1), Chunk.empty)
    } { st =>
      if st.buffer.isEmpty then Chunk.empty else Chunk.single(st.buffer)
    }

  def fixedSize[I](size: Int): Aux[I, Chunk[I], ChunkState[I]] =
    chunkBy(_ => 1, size)

  /**
   * Limits the number of bytes passing through. After the limit is exceeded,
   * no further elements are emitted.
   */
  def limitBytes(maxBytes: Long): Aux[Byte, Byte, Long] =
    stateful[Byte, Byte, Long](0L) { (count, b) =>
      val next = count + 1
      if next <= maxBytes then (next, Chunk.single(b)) else (next, Chunk.empty)
    }(_ => Chunk.empty)
end Scan
