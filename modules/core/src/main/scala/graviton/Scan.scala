package graviton

import zio.*
import zio.stream.*
import zio.ChunkBuilder
import zio.stream.Take
import zio.prelude.fx.ZPure
import scala.compiletime.erasedValue
import cats.Eval
import graviton.core.model.Block
import graviton.domain.HashBytes
import graviton.core.model.Size
import zio.prelude.{ForEach, given}
import scala.annotation.publicInBinary
import graviton.core.model.BoundedIntSize
/**
 * A stateful stream transformer whose state is represented as a Tuple.
 * Stateless scans use `EmptyTuple` and therefore do not alter the state type
 * when composed. Composition operators are `transparent inline` to encourage
 * compile-time optimisation and eliminate intermediate allocations.
 */
sealed trait Scan[-I, +O]:

  import Scan.{fromState as _, FromState as _FromState, ToState}

  type S <: Matchable

  type State = ToState[S]

  final type Size = scala.Tuple.Size[State]

  type FromState = _FromState[State, Size]

  inline def size: Size = compiletime
    .constValueOpt[Size]
    .getOrElse(initial.productArity.asInstanceOf[Size])

  private[graviton] def _initial: Eval[S]

  final def initialState: Eval[State] = _initial.map(Scan.toState)

  final def initial: State = initialState.value

  def fromState(state: State): FromState =
    if (state.productArity == 0) then ().asInstanceOf[FromState]
    else
      val n: Int = this.initial.productArity
      state.productIterator
        .take(n)
        .foldRight[Tuple](EmptyTuple)((o, e) => o *: e)
        .asInstanceOf[FromState]

  def step(state: State, in: I): (State, Chunk[O])

  def done(state: State): Chunk[O]

object Scan:

  type FromState[A <: Tuple & Matchable, Size <: Int] <: Matchable = A match
    case EmptyTuple => Unit
    case Tuple1[a]  => a & Matchable
    case _          => Tuple.Take[A, Size]

  inline def fromState[A <: Tuple & Matchable, Size <: Int](a: A): FromState[A, Size] = a match
    case EmptyTuple => ().asInstanceOf[FromState[A, Size]]
    case Tuple1(a)  => a.asInstanceOf[FromState[A, Size]]
    case t: Tuple   => t.take(compiletime.constValue[Size]).asInstanceOf[FromState[A, Size]]

  type ToState[A] <: Tuple = A match
    case x *: xs    => Tuple.Concat[ToState[x], ToState[xs]]
    case Tuple1[x]  => Tuple1[x]
    case EmptyTuple => EmptyTuple
    case Unit       => EmptyTuple
    case _          => Tuple1[A]

  def toState[A](a: A)(using ev: <:<[A, Matchable & A]): ToState[A] =
    ev(a) match
      case EmptyTuple    => EmptyTuple.asInstanceOf[ToState[A]]
      case _: Unit       => ().asInstanceOf[ToState[A]]
      case x *: xs       => (x *: xs).asInstanceOf[ToState[A]]
      case t @ Tuple1(_) => t.asInstanceOf[ToState[A]]
      case _             => Tuple1(ev(a)).asInstanceOf[ToState[A]]

  type Aux[-I, +O, St] = Scan[I, O] {
    type S = St
    // type State = ToState[S]
    // type Size = Tuple.Size[State]
  }

  extension [I, O, S](self: Scan.Aux[I, O, S])
    private def concatStates(a: Tuple, b: Tuple): Tuple =
      if a.productArity == 0 then b
      else if b.productArity == 0 then a
      else a ++ b

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

    def map[O2](f: O => O2)(using ev: S <:< (S & Matchable)): Scan.Aux[I, O2, (S & Matchable)] =
      stateful[I, O2, S & Matchable](ev(self._initial.value)) { (s: S, i: I) =>
        val (s2, o) = self.step(Scan.toState(ev(s)).asInstanceOf[self.State], i)
        (self.fromState(s2).asInstanceOf[S & Matchable], o.map(f))
      }(s => self.done(Scan.toState(ev(s)).asInstanceOf[self.State]).map(f))

    def contramap[I2](f: I2 => I)(using ev: S <:< (Matchable & S)): Scan.Aux[I2, O, S & Matchable] =
      stateful[I2, O, (S & Matchable)](ev(self._initial.value)) { (s: S, i2: I2) =>
        val (s2, o) = self.step(Scan.toState(ev(s)).asInstanceOf[self.State], f(i2))
        (self.fromState(s2).asInstanceOf[S & Matchable], o)
      }(s => self.done(Scan.toState(ev(s)).asInstanceOf[self.State]))

    def dimap[I2, O2](g: I2 => I)(f: O => O2)(using ev: S <:< (Matchable & S)): Scan.Aux[I2, O2, S & Matchable] =
      stateful[I2, O2, (S & Matchable)](ev(self._initial.value)) { (s: S, i2: I2) =>
        val (s2, o) = self.step(Scan.toState(ev(s)).asInstanceOf[self.State], g(i2))
        (self.fromState(s2).asInstanceOf[S & Matchable], o.map(f))
      }(s => self.done(Scan.toState(ev(s)).asInstanceOf[self.State]).map(f))

    def andThen[O2, S2 <: Matchable](
      that: Scan.Aux[O, O2, S2]
    )(using ev: S2 <:< Matchable): Scan.Aux[I, O2, Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]] =
      type SS = Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]
      val sizeA = self.initial.productArity.asInstanceOf[self.Size]
      statefulTuple[I, O2, SS](
        (self.initialState.flatMap(s => that.initialState.map(t => concatStates(s, (t)))).value).asInstanceOf[SS]
      ) { (st2: SS, i: I) =>
        val s1        = st2.take(sizeA).asInstanceOf[self.State]
        val s2        = (st2.drop(sizeA).asInstanceOf[that.State])
        val (s1b, o1) = self.step(s1.asInstanceOf[self.State], i)
        var s2b       = (s2).asInstanceOf[that.State]
        val builder   = ChunkBuilder.make[O2]()
        o1.foreach { o =>
          val (s2n, o2) = that.step(s2b, o)
          s2b = s2n
          builder ++= o2
        }
        (concatStates(s1b, s2b).asInstanceOf[SS], builder.result())
      } { (st: SS) =>
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
    )(using ev: S2 <:< Matchable): Scan.Aux[I, O2, Tuple.Concat[self.State, Tuple1[Option[ToState[S2]]]]] =
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
    )(using ev: S2 <:< (Matchable & S2)): Scan.Aux[I, (O, O2), Tuple.Concat[self.State, ToState[S2]]] =

      type CombinedState = Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]

      val combinedState: CombinedState =
        concatStates(self.initial, that.initial).asInstanceOf[CombinedState]

      val sizeA: Int = self.initial.productArity

      statefulTuple(combinedState)((st: CombinedState, i: I) =>
        val (s1, s2)  = st.splitAt(sizeA)
        val (s1b, o1) = self.step(s1.asInstanceOf[self.State], i)
        val (s2b, o2) = that.step(s2.asInstanceOf[that.State], i)
        (concatStates(s1b, s2b).asInstanceOf[CombinedState], o1.zip(o2))
      ) { st =>
        val (s1, s2) = st.splitAt(sizeA)
        val s1a      = s1.asInstanceOf[self.State]
        val s2a      = s2.asInstanceOf[that.State]
        self.done(s1a).zip(that.done(s2a))
      }

    transparent inline def runAll[F[+_], II <: I](
      inputs: F[II]
    )(using ev: S <:< Matchable): (self.State, Chunk[O]) =
      given ForEach[F] = compiletime.summonInline[ForEach[F]]
      val c = inputs.foldLeft((
        acc = ChunkBuilder.make[O](), 
        state = self.initial)
      ): (acc, i: I) =>
          val (s, out) = self.step(acc.state, i)
          (acc.acc.addAll(out), s)
      val aa = c.acc ++= self.done(c.state)
      (c.state, aa.result())

    transparent inline def runZPure(
      inputs: Iterable[I]
    )(using ev: S <:< (Matchable & S)): ZPure[Nothing, S, self.State, Any, Nothing, Chunk[O]] =
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
    final type S = Unit
    override val _initial: Eval[S]   = Eval.now(())
    def done(state: State): Chunk[O] = Chunk.empty
    def step(state: State, in: I)    = (state, f(in))

  
  final case class StatelessChain[I, O]  @publicInBinary (fs: Chunk[Any => Any])
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

  transparent inline def stateless1[I, O](f: I => O): Scan.Aux[I, O, Unit] =
    new StatelessChain[I, O](Chunk.single(f.asInstanceOf[Any => Any]))
  
  def stateless[I, O](
    f: I => Chunk[O]
  ): Stateless[I, O] =
    new Stateless[I, O](f) {}

  // ---------------------- Stateful helpers ----------------------

  sealed trait Stateful[St <: Matchable, -I, +O] extends Scan[I, O]:
    override final type S = St
    // override final type State = ToState[S]

  end Stateful

  object Stateful:
    given [I, O, St <: Matchable]
      => Conversion[
        Stateful[St, I, O],
        Scan.Aux[I, O, St],
      ] = self =>
      Scan.stateful(self._initial.value) { (s: St, i: I) =>
        self.step(Scan.toState(s).asInstanceOf[self.State], i) match
          case (s2, out) => (self.fromState(s2).asInstanceOf[St], out)
      }(s => self.done((toState(s).asInstanceOf[self.State]).asInstanceOf[self.State]))

  final case class DefaultStateful[I, O, St <: Matchable](_initial: Eval[St])(
    stepFn: (St, I) => (St, Chunk[O])
  )(onDone: St => Chunk[O])
      extends Stateful[St, I, O]:

    inline private def decode(a: State): St =
      inline erasedValue[St] match
        case _: Unit  => ().asInstanceOf[St]
        case _: Tuple => a.asInstanceOf[St]
        case _        =>
          if a.productArity == 0 then _initial.value.asInstanceOf[St]
          else a.asInstanceOf[Tuple1[Any]]._1.asInstanceOf[St]

    inline private def encode(s: St): State =
      inline erasedValue[St] match
        case _: Unit  => EmptyTuple.asInstanceOf[State]
        case _: Tuple => s.asInstanceOf[State]
        case _        => Tuple1(s).asInstanceOf[State]

    def step(state: State, in: I): (State, Chunk[O]) =
      val st     = decode(state)
      val (s, o) = stepFn(st, in)
      (encode(s), o)

    def done(state: State): Chunk[O] =
      onDone(decode(state))

  end DefaultStateful

  inline def stateful[I, O, St <: Matchable](_init: => St)(
    stepFn: (St, I) => (St, Chunk[O])
  )(doneFn: St => Chunk[O]): Stateful[St, I, O] =
    DefaultStateful(Eval.now(_init))((s: St, i: I) => 
      stepFn(s, i))(s => doneFn(s))

  def statefulTuple[I, O, St <: Matchable](_init: => St)(
    stepFn: (St, I) => (St, Chunk[O])
  )(doneFn: St => Chunk[O]): Stateful[St, I, O] =
    DefaultStateful(Eval.now(_init)) { (s: St, i: I) =>
      stepFn(s, i)
    }(s => doneFn(s))

  transparent inline def apply[I, O, SS <: Matchable](init: => SS)(
    inline stepFn: (SS, I) => (SS, Chunk[O])
  )(inline doneFn: SS => Chunk[O]): Stateful[SS, I, O] =
    stateful(init)((s: SS, i: I) => stepFn(s, i))(s => doneFn(s))

  // identity
  private[graviton] final case class Identity[A]() extends Scan[A, A] {
    type S = Unit
    override val _initial: Eval[S]   = Eval.now(())
    def done(state: State): Chunk[A] = Chunk.single(state.asInstanceOf[A])
    def step(state: State, in: A)    = (state, Chunk.single(in))
  }

  transparent inline def identity[I]: Aux[I, I, Unit] =
    new Identity[I]

  transparent inline def lift[I, O](inline f: I => O): Aux[I, O, Unit] =
    stateless1(f)

  // ---------------------- Built-in scans ----------------------

  /** Counts the number of elements in the incoming stream. */
  val count: Aux[Any, Long, Tuple1[Long]] =
    statefulTuple(Tuple1(0L)) { (s, in) =>
      val inc: Long =
        if in.isInstanceOf[zio.Chunk[?]] then in.asInstanceOf[zio.Chunk[?]].length.toLong
        else if in.isInstanceOf[Byte] then 1L
        else 1L
      ((Tuple1(s._1 + inc), Chunk.empty))
    }(s => Chunk.single(s._1))

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
  def hash(algo: graviton.HashAlgorithm): Aux[Block, Hash.SingleHash, HashState] =
    statefulTuple(
      algo match
        case HashAlgorithm.SHA256 => java.security.MessageDigest.getInstance("SHA-256")
        case HashAlgorithm.SHA512 => java.security.MessageDigest.getInstance("SHA-512")
        case HashAlgorithm.Blake3 => io.github.rctcwyvrn.blake3.Blake3.newInstance()
    ) { (m: HashState, b: Block) =>
      m -> Chunk.single(Hash(Hash.HashResult(algo, m.update(b.bytes.toChunk).digest)))
    }((m: HashState) => Chunk.single(Hash(Hash.HashResult(algo, m.digest))))

  /** Convenience scan that produces both hash and count in one pass. */
  def hashAndCount(
    algo: graviton.HashAlgorithm
  ): Aux[Block, (Hash.SingleHash, Long), (HashState, Long)] =
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
      ((m._1.update(b.bytes), m._2 + b.length.toLong), Chunk.empty[(Hash.SingleHash, Long)])
    } { (m: (HashState, Long)) =>
      Chunk.single((Hash(Hash.HashResult(algo, m._1.digest)), m._2))
    }

  // --------- chunking ---------

  final case class ChunkState[I, N] private[Scan] (buffer: ChunkBuilder[I], cost: N):

    type Flushed = (flushed: Chunk[I], newState: ChunkState[I, N])

    def toChunk: Chunk[I] = buffer.result()

    def addAll(in: Chunk[I], costFn: Chunk[I] => N)(using I: Integral[N]): ChunkState[I, N] =
      copy(
        buffer = buffer.addAll(in),
        cost = in.foldLeft(cost)((acc, i) => I.plus(acc, costFn(Chunk.single(i)))),
      )

    def flushIf(threshold: N, limit: Option[N] = None)(using I: Integral[N]): Flushed =
      val (flushed = f, newState = ns) = if I.gteq(cost, threshold) then reset() else (Chunk.empty, this)
      val (splitL, splitR) = limit.fold(f -> Chunk.empty[I])(l => f.splitAt(I.toInt(l)))
      splitL -> copy(
        buffer = ChunkBuilder.make[I]().addAll(splitR), 
        cost = I.minus(cost, I.fromInt(splitL.length))
      )

    def reset()(using I: Integral[N]): Flushed =
      synchronized {
        val res = buffer.result()
        buffer.clear()
        (flushed = res, newState = copy(cost = I.zero))
      }

    def knownSize: Option[Size] = Size.option(buffer.knownSize)

  def chunkBy[I, N, M](
    costFn: Chunk[I] => N,
    threshold: N,
  )(
    limit: Option[M] = None
  )(using I: Integral[N], M: Integral[M]): Aux[Chunk[I], NonEmptyChunk[I], ChunkState[I, N]] =
    statefulTuple(ChunkState(limit.fold(ChunkBuilder.make[I]())(l => ChunkBuilder.make[I](M.toInt(l))), I.zero)) {
      (st: ChunkState[I, N], in: Chunk[I]) =>
        val buf1                         = st.addAll(in, costFn)
        val (flushed = f, newState = ns) = buf1.flushIf(threshold)
        (ns, Chunk.fromIterable(NonEmptyChunk.fromChunk(f)))
    } { st =>
      val (flushed = f, newState = ns) = st.flushIf(threshold)
      Chunk.fromIterable(NonEmptyChunk.fromChunk(f)) ++
        Chunk.fromIterable(NonEmptyChunk.fromChunk(ns.toChunk))
    }

  def fixedSize[I <: BoundedIntSize[? <: Int, ? <: Int]](
    size: Int, 
    limit: Option[Int] = None
  )(using I: BoundedIntSize[? <: Int, ? <: Int]): Aux[Chunk[Byte], Block, ChunkState[Byte, I.T]] =
    import I.given_Integral_T.*
    chunkBy[Byte, I.T, I.T](_ => 
      I.one, 
      fromInt(size)
      )(limit
      .map(fromInt)
      .flatMap(I.option(_))
      .orElse(Some(I.max)))
      .map(Block.applyUnsafe(_))

end Scan
