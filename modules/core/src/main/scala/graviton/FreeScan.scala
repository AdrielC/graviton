package graviton

import zio.*

import cats.Eval

/**
 * A free, compositional description of a Scan. It can be built up
 * using pure combinators and later compiled into a concrete `Scan`
 * whose `State` type is derived at compile time.
 */
sealed trait FreeScan[-I, +O]:

  type State <: Matchable

  /** Compile this free description into a concrete `Scan`. */
  def compile: Scan.Aux[I, O, State]

  /** Compile with a small optimization pass to simplify the graph. */
  def compileOptimized: Scan.Aux[I, O, State] =
    FreeScan.optimize(this).compile.asInstanceOf[Scan.Aux[I, O, State]]

object FreeScan:
  type Aux[-I, +O, S <: Matchable] = FreeScan[I, O] { type State = S }

  // ---------------- Constructors ----------------

  final case class Identity[I]() extends FreeScan[I, I]:
    type State = EmptyTuple
    def compile: Scan.Aux[I, I, EmptyTuple] = new Scan.Identity[I]()

  final case class Stateless1[I, O](f: I => O) extends FreeScan[I, O]:
    type State = EmptyTuple
    def compile: Scan.Aux[I, O, EmptyTuple] = Scan.stateless1(f)

  final case class Stateless[I, O](f: I => Chunk[O]) extends FreeScan[I, O]:
    type State = EmptyTuple
    def compile: Scan.Aux[I, O, EmptyTuple] = Scan.stateless(f)

  final case class Stateful[I, O, S <: Tuple](
    init: S,
    stepFn: (S, I) => (S, Chunk[O]),
    doneFn: S => Chunk[O],
  ) extends FreeScan[I, O]:
    type State = S
    def compile: Scan.Aux[I, O, S] = Scan.statefulTuple(init)(stepFn)(doneFn)

  // ---------------- Combinators ----------------

  extension [I, O, S <: Matchable](self: FreeScan.Aux[I, O, S])
    def map[O2](f: O => O2): FreeScan.Aux[I, O2, S] =
      Mapped(self, f)

    def contramap[I2](g: I2 => I): FreeScan[I2, O] =
      ContraMapped(self, g)

    def dimap[I2, O2](g: I2 => I)(f: O => O2): FreeScan[I2, O2] =
      Dimapped(self, g, f)

    def andThen[O2, S2 <: Matchable](that: FreeScan.Aux[O, O2, S2]): FreeScan.Aux[I, O2, Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]] =
      Composed[I, O, S, O2, S2](self, that)

    def zip[O2, S2 <: Matchable](that: FreeScan.Aux[I, O2, S2]): FreeScan.Aux[I, (O, O2), Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]] =
      Zipped[I, O, S, O2, S2](self, that)

    // --- Category aliases
    infix def >>>[O2, S2 <: Matchable](that: FreeScan.Aux[O, O2, S2]): FreeScan[I, O2] =
      andThen(that)

    infix def <<<[H, S2 <: Matchable](that: FreeScan[H, I]): FreeScan[H, O] =
      that.andThen(self)

    // --- Fanout (same input to both)
    infix def &&&[O2, S2 <: Matchable](that: FreeScan.Aux[I, O2, S2]): FreeScan[I, (O, O2)] =
      zip(that)

    // --- Product on pairs: (I, I2) => (O, O2)
    infix def ***[I2, O2, S2 <: Matchable](that: FreeScan.Aux[I2, O2, S2]): FreeScan[(I, I2), (O, O2)] =
      Product[I, O, S, I2, O2, S2](self, that)

    // --- Lift to first/second of a pair
    def first[C]: FreeScan.Aux[(I, C), (O, C), Tuple.Concat[Scan.ToState[S], Tuple1[Option[C]]]] =
      First(self)

    def second[C]: FreeScan.Aux[(C, I), (C, O), Tuple.Concat[Tuple1[Option[C]], Scan.ToState[S]]] =
      Second(self)

    // --- Choice on Either
    def left[C]: FreeScan.Aux[Either[I, C], Either[O, C], S] =
      LeftChoice(self)

    def right[C]: FreeScan.Aux[Either[C, I], Either[C, O], S] =
      RightChoice(self)

    infix def +++[I2, O2, S2 <: Matchable](
      that: FreeScan.Aux[I2, O2, S2]
    ): FreeScan.Aux[Either[I, I2], Either[O, O2], Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]] =
      PlusPlus(self, that)

    infix def |||[I2, S2 <: Matchable](
      that: FreeScan.Aux[I2, O, S2]
    ): FreeScan.Aux[Either[I, I2], O, Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]] =
      FanIn(self, that)

    /** Alias for `compile`. */
    def toScan: Scan.Aux[I, O, S] = self.compile

  final case class Mapped[I, O, S <: Matchable, O2](
    src: FreeScan.Aux[I, O, S],
    f: O => O2,
  ) extends FreeScan[I, O2]:
    type State = S
    def compile: Scan.Aux[I, O2, S] = src.compile.map(f)

  final case class ContraMapped[I, O, S <: Matchable, I2](
    src: FreeScan.Aux[I, O, S],
    g: I2 => I,
  ) extends FreeScan[I2, O]:
    type State = S
    def compile: Scan.Aux[I2, O, S] = src.compile.contramap(g)

  final case class Dimapped[I, O, S <: Matchable, I2, O2](
    src: FreeScan.Aux[I, O, S],
    g: I2 => I,
    f: O => O2,
  ) extends FreeScan[I2, O2]:
    type State = S
    def compile: Scan.Aux[I2, O2, S] = src.compile.dimap(g)(f)

  final case class Composed[I, O, S <: Matchable, O2, S2 <: Matchable](
    left: FreeScan.Aux[I, O, S],
    right: FreeScan.Aux[O, O2, S2],
  ) extends FreeScan[I, O2]:
    type State = Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]
    def compile: Scan.Aux[I, O2, State] =
      val r = right.compile.asInstanceOf[Scan.Aux[O, O2, right.State]]
      val l = left.compile.asInstanceOf[Scan.Aux[I, O, left.State]]
      l.andThen(r)

  final case class Zipped[I, O, S <: Matchable, O2, S2 <: Matchable](
    left: FreeScan.Aux[I, O, S],
    right: FreeScan.Aux[I, O2, S2],
  ) extends FreeScan[I, (O, O2)]:
    override final type State = Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]
    transparent inline def compile: Scan.Aux[I, (O, O2), State] =
      ???

  final case class Product[I1, O1, S1 <: Matchable, I2, O2, S2 <: Matchable](
    left: FreeScan.Aux[I1, O1, S1],
    right: FreeScan.Aux[I2, O2, S2],
  ) extends FreeScan[(I1, I2), (O1, O2)]:
    type State = Tuple.Concat[Scan.ToState[S1], Scan.ToState[S2]]
    def compile: Scan.Aux[(I1, I2), (O1, O2), State] =
      val a     = left.compile
      val b     = right.compile
      val sizeA = a.initial.productArity
      Scan.statefulTuple[(I1, I2), (O1, O2), State](
        a.initial ++ b.initial
      ) { (st, in) =>
        val s1        = st.take(sizeA).asInstanceOf[left.State]
        val s2        = st.drop(sizeA).asInstanceOf[right.State]
        val (i1, i2)  = in
        val (s1b, o1) = a.step(Scan.toState(s1), i1)
        val (s2b, o2) = b.step(Scan.toState(s2), i2)
        ((s1b ++ s2b).asInstanceOf[State], o1.zip(o2))
      } { st =>
        val s1 = st.take(sizeA).asInstanceOf[a.State]
        val s2 = st.drop(sizeA).asInstanceOf[b.State]
        a.done(s1).zip(b.done(s2))
      }

  final case class First[I, O, S <: Matchable, C](
    src: FreeScan.Aux[I, O, S]
  ) extends FreeScan[(I, C), (O, C)]:
    type State = Tuple.Concat[Scan.ToState[S], Tuple1[Option[C]]]
    def compile: Scan.Aux[(I, C), (O, C), State] =
      val a                      = src.compile
      inline given sizeA: a.Size = a.initial.productArity.asInstanceOf[Tuple.Size[a.State]]

      Scan.statefulTuple[(I, C), (O, C), State](
        Eval.now(a.initial ++ Tuple1(Option.empty[C]))
      ) { (st, in) =>
        val sA        = st.take(sizeA).asInstanceOf[src.State]
        val (i, c)    = in
        val (sAb, oA) = a.step(Scan.toState(sA).asInstanceOf[a.State], i)
        ((sAb ++ Tuple1(Some(c))).asInstanceOf[this.State], oA.map(o => (o, c)))
      } { st =>
        val sA       = st.take(sizeA).asInstanceOf[src.State]
        val lastCOpt = st.drop(sizeA).asInstanceOf[Tuple1[Option[C]]]._1
        lastCOpt match
          case Some(c) => a.done(Scan.toState(sA).asInstanceOf[a.State]).map(o => (o, c))
          case None    => Chunk.empty
      }

  final case class Second[I, O, S <: Matchable, C](
    src: FreeScan.Aux[I, O, S]
  ) extends FreeScan[(C, I), (C, O)]:
    type State = Tuple.Concat[Tuple1[Option[C]], Scan.ToState[S]]
    def compile: Scan.Aux[(C, I), (C, O), State] =
      val a     = src.compile.asInstanceOf[Scan.Aux[I, O, src.State]]
      val sizeA = 1 // Tuple1[Option[C]]
      Scan.statefulTuple[(C, I), (C, O), this.State](Eval.now(Option.empty[C] *: a.initial)) { (st, in) =>
        val sA       = st.drop(sizeA).asInstanceOf[a.State]
        val (c, i)   = in
        val (sAb, o) = a.step(sA, i)
        ((Tuple1(Some(c)) *: sAb).asInstanceOf[this.State], o.map(o2 => (c, o2)))
      } { st =>
        val lastCOpt = st.take(sizeA).asInstanceOf[Tuple1[Option[C]]]._1
        val sA       = st.drop(sizeA).asInstanceOf[a.State]
        lastCOpt match
          case Some(c) => a.done(sA).map(o => (c, o))
          case None    => Chunk.empty
      }

  final case class LeftChoice[I, O, S <: Matchable, C](
    src: FreeScan.Aux[I, O, S]
  ) extends FreeScan[Either[I, C], Either[O, C]]:
    type State = S
    def compile: Scan.Aux[Either[I, C], Either[O, C], S] =
      val a = src.compile
      Scan.statefulTuple[Either[I, C], Either[O, C], this.State](Eval.now(a.initial.asInstanceOf[this.State])) { (s, in) =>
        in match
          case Left(i)  =>
            val (s2, out) = a.step(Scan.toState(s).asInstanceOf[a.State], i)
            (Scan.toState(s2).asInstanceOf[this.State], out.map(Left(_)))
          case Right(c) => (s, Chunk.single(Right(c)))
      }(s => a.done(Scan.toState(s).asInstanceOf[a.State]).map(Left(_)))

  final case class RightChoice[I, O, S <: Matchable, C](
    src: FreeScan.Aux[I, O, S]
  ) extends FreeScan[Either[C, I], Either[C, O]]:
    type State = S
    def compile: Scan.Aux[Either[C, I], Either[C, O], S] =
      val a = src.compile
      Scan.statefulTuple[Either[C, I], Either[C, O], this.State](Eval.now(a.initial.asInstanceOf[this.State])) { (s, in) =>
        in match
          case Right(i) =>
            val (s2, out) = a.step(Scan.toState(s).asInstanceOf[a.State], i)
            (Scan.toState(s2).asInstanceOf[this.State], out.map(Right(_)))
          case Left(c)  => (Scan.toState(s).asInstanceOf[this.State], Chunk.single(Left(c)))
      }(s => a.done(Scan.toState(s).asInstanceOf[a.State]).map(Right(_)))

  final case class PlusPlus[I1, O1, S1 <: Matchable, I2, O2, S2 <: Matchable](
    left: FreeScan.Aux[I1, O1, S1],
    right: FreeScan.Aux[I2, O2, S2],
  ) extends FreeScan[Either[I1, I2], Either[O1, O2]]:
    type State = Tuple.Concat[Scan.ToState[S1], Scan.ToState[S2]]
    def compile: Scan.Aux[Either[I1, I2], Either[O1, O2], State] =
      val a     = left.compile
      val b     = right.compile
      val sizeA = a.initial.productArity
      Scan.statefulTuple[Either[I1, I2], Either[O1, O2], this.State](Eval.now(a.initial ++ b.initial)) { (st, in) =>
        val s1 = st.take(sizeA).asInstanceOf[a.State]
        val s2 = st.drop(sizeA).asInstanceOf[b.State]
        in match
          case Left(i1)  =>
            val (s1b, o1) = a.step(s1, i1)
            ((s1b ++ s2).asInstanceOf[Tuple.Concat[a.State, b.State]], o1.map(Left(_)))
          case Right(i2) =>
            val (s2b, o2) = b.step(s2, i2)
            ((s1 ++ s2b).asInstanceOf[Tuple.Concat[a.State, b.State]], o2.map(Right(_)))
      } { st =>
        val s1 = st.take(sizeA).asInstanceOf[a.State]
        val s2 = st.drop(sizeA).asInstanceOf[b.State]
        a.done(s1).map(Left(_)) ++ b.done(s2).map(Right(_))
      }

  final case class FanIn[I1, O, S1 <: Matchable, I2, S2 <: Matchable](
    left: FreeScan.Aux[I1, O, S1],
    right: FreeScan.Aux[I2, O, S2],
  ) extends FreeScan[Either[I1, I2], O]:
    type State = Tuple.Concat[Scan.ToState[S1], Scan.ToState[S2]]
    def compile: Scan.Aux[Either[I1, I2], O, State] =
      val a     = left.compile
      val b     = right.compile
      val sizeA = a.initial.productArity
      Scan.statefulTuple[Either[I1, I2], O, this.State](Eval.later(a.initial ++ b.initial)) { (st, in) =>
        val s1 = st.take(sizeA).asInstanceOf[a.State]
        val s2 = st.drop(sizeA).asInstanceOf[b.State]
        in match
          case Left(i1)  =>
            val (s1b, o1) = a.step(s1, i1)
            ((s1b ++ s2).asInstanceOf[Tuple.Concat[a.State, b.State]], o1)
          case Right(i2) =>
            val (s2b, o2) = b.step(s2, i2)
            ((s1 ++ s2b).asInstanceOf[Tuple.Concat[a.State, b.State]], o2)
      } { st =>
        val s1 = st.take(sizeA).asInstanceOf[a.State]
        val s2 = st.drop(sizeA).asInstanceOf[b.State]
        a.done(s1) ++ b.done(s2)
      }

  // ---------------- Helpers ----------------

  def identity[I]: Aux[I, I, EmptyTuple] = Identity[I]()

  def lift[I, O](f: I => O): Aux[I, O, EmptyTuple] = Stateless1(f)

  def stateless1[I, O](f: I => O): Aux[I, O, EmptyTuple] = Stateless1(f)

  def stateless[I, O](f: I => Chunk[O]): Aux[I, O, EmptyTuple] = Stateless(f)

  def stateful[I, O, S](init: S)(
    stepFn: (S, I) => (S, Chunk[O])
  )(
    doneFn: S => Chunk[O]
  ): Aux[I, O, S *: EmptyTuple] =
    Stateful[I, O, S *: EmptyTuple](
      init = init *: EmptyTuple,
      stepFn = (st: S *: EmptyTuple, i: I) =>
        val (s, outs) = stepFn(st.head, i)
        (s *: EmptyTuple, outs)
      ,
      doneFn = (st: S *: EmptyTuple) => doneFn(st.head),
    )

  def statefulTuple[I, O, S <: Tuple](init: S)(
    stepFn: (S, I) => (S, Chunk[O])
  )(
    doneFn: S => Chunk[O]
  ): Aux[I, O, S] =
    Stateful(init, stepFn, doneFn)

  // ---------------- Optimization ----------------

  private def optimize[I, O, S <: Matchable](fs: FreeScan.Aux[I, O, S]): FreeScan.Aux[I, O, S] =
    fs match
      // Eliminate identities
      case Composed(l, r) if l.isInstanceOf[Identity[?]] => r.asInstanceOf[FreeScan.Aux[I, O, S]]
      case Composed(l, r) if r.isInstanceOf[Identity[?]] => l.asInstanceOf[FreeScan.Aux[I, O, S]]
      // Fuse mapped chains
      case Mapped(Mapped(src, f1), f2)                   => optimize(Mapped(src, f1.andThen(f2))).asInstanceOf[FreeScan.Aux[I, O, S]]
      // Fuse contramaps
      case ContraMapped(ContraMapped(src, g1), g2)       => optimize(ContraMapped(src, g2.andThen(g1))).asInstanceOf[FreeScan.Aux[I, O, S]]
      // Distribute map over composition where possible
      case Mapped(Composed(a, b), f)                     => optimize(Composed(a, Mapped(b, f))).asInstanceOf[FreeScan.Aux[I, O, S]]
      // Distribute contramap over composition
      case ContraMapped(Composed(a, b), g)               => optimize(Composed(ContraMapped(a, g), b)).asInstanceOf[FreeScan.Aux[I, O, S]]
      // Default: recursively optimize children
      case Mapped(src, f)                                => Mapped(optimize(src), f).asInstanceOf[FreeScan.Aux[I, O, S]]
      case ContraMapped(src, g)                          => ContraMapped(optimize(src), g).asInstanceOf[FreeScan.Aux[I, O, S]]
      case Dimapped(src, g, f)                           => Dimapped(optimize(src), g, f).asInstanceOf[FreeScan.Aux[I, O, S]]
      case Composed(l, r)                                => Composed(optimize(l), optimize(r)).asInstanceOf[FreeScan.Aux[I, O, S]]
      case Zipped(l, r)                                  => Zipped(optimize(l), optimize(r)).asInstanceOf[FreeScan.Aux[I, O, S]]
      case Product(l, r)                                 => Product(optimize(l), optimize(r)).asInstanceOf[FreeScan.Aux[I, O, S]]
      case First(src)                                    => First(optimize(src)).asInstanceOf[FreeScan.Aux[I, O, S]]
      case Second(src)                                   => Second(optimize(src)).asInstanceOf[FreeScan.Aux[I, O, S]]
      case LeftChoice(src)                               => LeftChoice(optimize(src)).asInstanceOf[FreeScan.Aux[I, O, S]]
      case RightChoice(src)                              => RightChoice(optimize(src)).asInstanceOf[FreeScan.Aux[I, O, S]]
      case PlusPlus(l, r)                                => PlusPlus(optimize(l), optimize(r)).asInstanceOf[FreeScan.Aux[I, O, S]]
      case FanIn(l, r)                                   => FanIn(optimize(l), optimize(r)).asInstanceOf[FreeScan.Aux[I, O, S]]
      case other                                         => other
