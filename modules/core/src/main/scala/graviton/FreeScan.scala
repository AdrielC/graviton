package graviton

import zio.*

/**
 * A free, compositional description of a Scan. It can be built up
 * using pure combinators and later compiled into a concrete `Scan`
 * whose `State` type is derived at compile time.
 */
sealed trait FreeScan[-I, +O]:
  type State <: Tuple

  /** Compile this free description into a concrete `Scan`. */
  def compile: Scan.Aux[I, O, State]

object FreeScan:
  type Aux[-I, +O, S <: Tuple] = FreeScan[I, O] { type State = S }

  // ---------------- Constructors ----------------

  final case class Identity[I]() extends FreeScan[I, I]:
    type State = EmptyTuple
    def compile: Scan.Aux[I, I, EmptyTuple] = Scan.identity[I]

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

  extension [I, O, S <: Tuple](self: FreeScan.Aux[I, O, S])
    def map[O2](f: O => O2): FreeScan.Aux[I, O2, S] =
      Mapped(self, f)

    def contramap[I2](g: I2 => I): FreeScan.Aux[I2, O, S] =
      ContraMapped(self, g)

    def dimap[I2, O2](g: I2 => I)(f: O => O2): FreeScan.Aux[I2, O2, S] =
      Dimapped(self, g, f)

    def andThen[O2, S2 <: Tuple](that: FreeScan.Aux[O, O2, S2]): FreeScan.Aux[I, O2, Tuple.Concat[S, S2]] =
      Composed(self, that)

    def zip[O2, S2 <: Tuple](that: FreeScan.Aux[I, O2, S2]): FreeScan.Aux[I, (O, O2), Tuple.Concat[S, S2]] =
      Zipped(self, that)

    // --- Category aliases
    infix def >>>[O2, S2 <: Tuple](that: FreeScan.Aux[O, O2, S2]): FreeScan.Aux[I, O2, Tuple.Concat[S, S2]] =
      andThen(that)

    infix def <<<[H, S2 <: Tuple](that: FreeScan.Aux[H, I, S2]): FreeScan.Aux[H, O, Tuple.Concat[S2, S]] =
      that.andThen(self)

    // --- Fanout (same input to both)
    infix def &&&[O2, S2 <: Tuple](that: FreeScan.Aux[I, O2, S2]): FreeScan.Aux[I, (O, O2), Tuple.Concat[S, S2]] =
      zip(that)

    // --- Product on pairs: (I, I2) => (O, O2)
    infix def ***[I2, O2, S2 <: Tuple](that: FreeScan.Aux[I2, O2, S2]): FreeScan.Aux[(I, I2), (O, O2), Tuple.Concat[S, S2]] =
      Product(self, that)

    // --- Lift to first/second of a pair
    def first[C]: FreeScan.Aux[(I, C), (O, C), Tuple.Concat[S, Tuple1[Option[C]]]] =
      First(self)

    def second[C]: FreeScan.Aux[(C, I), (C, O), Tuple.Concat[Tuple1[Option[C]], S]] =
      Second(self)

    // --- Choice on Either
    def left[C]: FreeScan.Aux[Either[I, C], Either[O, C], S] =
      LeftChoice(self)

    def right[C]: FreeScan.Aux[Either[C, I], Either[C, O], S] =
      RightChoice(self)

    infix def +++[I2, O2, S2 <: Tuple](that: FreeScan.Aux[I2, O2, S2]): FreeScan.Aux[Either[I, I2], Either[O, O2], Tuple.Concat[S, S2]] =
      PlusPlus(self, that)

    infix def |||[I2, S2 <: Tuple](that: FreeScan.Aux[I2, O, S2]): FreeScan.Aux[Either[I, I2], O, Tuple.Concat[S, S2]] =
      FanIn(self, that)

    /** Alias for `compile`. */
    def toScan: Scan.Aux[I, O, S] = self.compile

  final case class Mapped[I, O, S <: Tuple, O2](
    src: FreeScan.Aux[I, O, S],
    f: O => O2,
  ) extends FreeScan[I, O2]:
    type State = S
    def compile: Scan.Aux[I, O2, S] = src.compile.map(f)

  final case class ContraMapped[I, O, S <: Tuple, I2](
    src: FreeScan.Aux[I, O, S],
    g: I2 => I,
  ) extends FreeScan[I2, O]:
    type State = S
    def compile: Scan.Aux[I2, O, S] = src.compile.contramap(g)

  final case class Dimapped[I, O, S <: Tuple, I2, O2](
    src: FreeScan.Aux[I, O, S],
    g: I2 => I,
    f: O => O2,
  ) extends FreeScan[I2, O2]:
    type State = S
    def compile: Scan.Aux[I2, O2, S] = src.compile.dimap(g)(f)

  final case class Composed[I, O, S <: Tuple, O2, S2 <: Tuple](
    left: FreeScan.Aux[I, O, S],
    right: FreeScan.Aux[O, O2, S2],
  ) extends FreeScan[I, O2]:
    type State = Tuple.Concat[S, S2]
    def compile: Scan.Aux[I, O2, Tuple.Concat[S, S2]] = left.compile.andThen(right.compile)

  final case class Zipped[I, O, S <: Tuple, O2, S2 <: Tuple](
    left: FreeScan.Aux[I, O, S],
    right: FreeScan.Aux[I, O2, S2],
  ) extends FreeScan[I, (O, O2)]:
    type State = Tuple.Concat[S, S2]
    def compile: Scan.Aux[I, (O, O2), Tuple.Concat[S, S2]] = left.compile.zip(right.compile)

  final case class Product[I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left: FreeScan.Aux[I1, O1, S1],
    right: FreeScan.Aux[I2, O2, S2],
  ) extends FreeScan[(I1, I2), (O1, O2)]:
    type State = Tuple.Concat[S1, S2]
    def compile: Scan.Aux[(I1, I2), (O1, O2), Tuple.Concat[S1, S2]] =
      val a      = left.compile
      val b      = right.compile
      val sizeA  = a.initial.productArity
      Scan.statefulTuple[(I1, I2), (O1, O2), Tuple.Concat[a.State, b.State]](a.initial ++ b.initial) { (st, in) =>
        val s1        = st.take(sizeA).asInstanceOf[a.State]
        val s2        = st.drop(sizeA).asInstanceOf[b.State]
        val (i1, i2)  = in
        val (s1b, o1) = a.step(s1, i1)
        val (s2b, o2) = b.step(s2, i2)
        ((s1b ++ s2b).asInstanceOf[Tuple.Concat[a.State, b.State]], o1.zip(o2))
      } { st =>
        val s1 = st.take(sizeA).asInstanceOf[a.State]
        val s2 = st.drop(sizeA).asInstanceOf[b.State]
        a.done(s1).zip(b.done(s2))
      }

  final case class First[I, O, S <: Tuple, C](
    src: FreeScan.Aux[I, O, S]
  ) extends FreeScan[(I, C), (O, C)]:
    type State = Tuple.Concat[S, Tuple1[Option[C]]]
    def compile: Scan.Aux[(I, C), (O, C), State] =
      val a     = src.compile
      val sizeA = a.initial.productArity
      Scan.statefulTuple[(I, C), (O, C), Tuple.Concat[a.State, Tuple1[Option[C]]]](a.initial ++ Tuple1(None)) { (st, in) =>
        val sA        = st.take(sizeA).asInstanceOf[a.State]
        val (i, c)    = in
        val (sAb, oA) = a.step(sA, i)
        ((sAb ++ Tuple1(Some(c))).asInstanceOf[Tuple.Concat[a.State, Tuple1[Option[C]]]], oA.map(o => (o, c)))
      } { st =>
        val sA       = st.take(sizeA).asInstanceOf[a.State]
        val lastCOpt = st.drop(sizeA).asInstanceOf[Tuple1[Option[C]]]._1
        lastCOpt match
          case Some(c) => a.done(sA).map(o => (o, c))
          case None    => Chunk.empty
      }

  final case class Second[I, O, S <: Tuple, C](
    src: FreeScan.Aux[I, O, S]
  ) extends FreeScan[(C, I), (C, O)]:
    type State = Tuple.Concat[Tuple1[Option[C]], S]
    def compile: Scan.Aux[(C, I), (C, O), State] =
      val a     = src.compile
      val sizeA = 1 // Tuple1[Option[C]]
      Scan.statefulTuple[(C, I), (C, O), Tuple.Concat[Tuple1[Option[C]], a.State]](Tuple1(None) ++ a.initial) { (st, in) =>
        val sA       = st.drop(sizeA).asInstanceOf[a.State]
        val (c, i)   = in
        val (sAb, o) = a.step(sA, i)
        ((Tuple1(Some(c)) ++ sAb).asInstanceOf[Tuple.Concat[Tuple1[Option[C]], a.State]], o.map(o2 => (c, o2)))
      } { st =>
        val lastCOpt = st.take(sizeA).asInstanceOf[Tuple1[Option[C]]]._1
        val sA       = st.drop(sizeA).asInstanceOf[a.State]
        lastCOpt match
          case Some(c) => a.done(sA).map(o => (c, o))
          case None    => Chunk.empty
      }

  final case class LeftChoice[I, O, S <: Tuple, C](
    src: FreeScan.Aux[I, O, S]
  ) extends FreeScan[Either[I, C], Either[O, C]]:
    type State = S
    def compile: Scan.Aux[Either[I, C], Either[O, C], S] =
      val a = src.compile
      Scan.statefulTuple[Either[I, C], Either[O, C], a.State](a.initial) { (s, in) =>
        in match
          case Left(i)  =>
            val (s2, out) = a.step(s, i)
            (s2, out.map(Left(_)))
          case Right(c) => (s, Chunk.single(Right(c)))
      }(s => a.done(s).map(Left(_)))

  final case class RightChoice[I, O, S <: Tuple, C](
    src: FreeScan.Aux[I, O, S]
  ) extends FreeScan[Either[C, I], Either[C, O]]:
    type State = S
    def compile: Scan.Aux[Either[C, I], Either[C, O], S] =
      val a = src.compile
      Scan.statefulTuple[Either[C, I], Either[C, O], a.State](a.initial) { (s, in) =>
        in match
          case Right(i) =>
            val (s2, out) = a.step(s, i)
            (s2, out.map(Right(_)))
          case Left(c)  => (s, Chunk.single(Left(c)))
      }(s => a.done(s).map(Right(_)))

  final case class PlusPlus[I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left: FreeScan.Aux[I1, O1, S1],
    right: FreeScan.Aux[I2, O2, S2],
  ) extends FreeScan[Either[I1, I2], Either[O1, O2]]:
    type State = Tuple.Concat[S1, S2]
    def compile: Scan.Aux[Either[I1, I2], Either[O1, O2], Tuple.Concat[S1, S2]] =
      val a     = left.compile
      val b     = right.compile
      val sizeA = a.initial.productArity
      Scan.statefulTuple[Either[I1, I2], Either[O1, O2], Tuple.Concat[a.State, b.State]](a.initial ++ b.initial) {
        (st, in) =>
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

  final case class FanIn[I1, O, S1 <: Tuple, I2, S2 <: Tuple](
    left: FreeScan.Aux[I1, O, S1],
    right: FreeScan.Aux[I2, O, S2],
  ) extends FreeScan[Either[I1, I2], O]:
    type State = Tuple.Concat[S1, S2]
    def compile: Scan.Aux[Either[I1, I2], O, Tuple.Concat[S1, S2]] =
      val a     = left.compile
      val b     = right.compile
      val sizeA = a.initial.productArity
      Scan.statefulTuple[Either[I1, I2], O, Tuple.Concat[a.State, b.State]](a.initial ++ b.initial) { (st, in) =>
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
        (s *: EmptyTuple, outs),
      doneFn = (st: S *: EmptyTuple) => doneFn(st.head),
    )

  def statefulTuple[I, O, S <: Tuple](init: S)(
    stepFn: (S, I) => (S, Chunk[O])
  )(
    doneFn: S => Chunk[O]
  ): Aux[I, O, S] =
    Stateful(init, stepFn, doneFn)

