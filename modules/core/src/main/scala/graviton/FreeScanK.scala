package graviton

import zio.*
import zio.schema.Schema
// import scala.compiletime.constValue

/**
 * A pure-data Free Arrow for scans, parameterized by a domain DSL F.
 * Leaves are F[A, B] values that must encode their State type via a type member.
 * No lambdas or closures are stored in this structure; interpretation happens via Interpreter.
 */
sealed trait FreeScanK[F[_, _], -I, +O]:
  type State <: Tuple
  def compile(using FreeScanK.Interpreter[F]): Scan.Aux[I, O, State]
  def compileOptimized(using FreeScanK.Interpreter[F]): Scan.Aux[I, O, State] =
    FreeScanK.optimize(this).compile

object FreeScanK:
  type Aux[F[_, _], -I, +O, S <: Tuple] = FreeScanK[F, I, O] { type State = S }

  // Interpreter from DSL to concrete Scan
  trait Interpreter[F[_, _]]:
    def toScan[I, O, S <: Tuple](op: F[I, O] { type State = S }): Scan.Aux[I, O, S]
    def stateSchema[I, O, S <: Tuple](op: F[I, O] { type State = S }): Schema[S]
    def stateLabels[I, O, S <: Tuple](op: F[I, O] { type State = S }): Chunk[String]

  // Constructors
  final case class Id[F[_, _], A]() extends FreeScanK[F, A, A]:
    type State = EmptyTuple
    def compile(using Interpreter[F]): Scan.Aux[A, A, EmptyTuple] = Scan.identity[A]

  final case class Op[F[_, _], I, O, S <: Tuple](fab: F[I, O] { type State = S }) extends FreeScanK[F, I, O]:
    type State = S
    def compile(using i: Interpreter[F]): Scan.Aux[I, O, S] = i.toScan(fab)
    def schema(using i: Interpreter[F]): Schema[S]          = i.stateSchema(fab)
    def labels(using i: Interpreter[F]): Chunk[String]      = i.stateLabels(fab)

  final case class AndThen[F[_, _], A, B, C, S1 <: Tuple, S2 <: Tuple](
    left: FreeScanK.Aux[F, A, B, S1],
    right: FreeScanK.Aux[F, B, C, S2],
  ) extends FreeScanK[F, A, C]:
    type State = Tuple.Concat[S1, S2]
    def compile(using Interpreter[F]): Scan.Aux[A, C, State] = left.compile.andThen(right.compile)

  final case class Zip[F[_, _], I, O1, O2, S1 <: Tuple, S2 <: Tuple](
    left: FreeScanK.Aux[F, I, O1, S1],
    right: FreeScanK.Aux[F, I, O2, S2],
  ) extends FreeScanK[F, I, (O1, O2)]:
    type State = Tuple.Concat[S1, S2]
    def compile(using Interpreter[F]): Scan.Aux[I, (O1, O2), State] = left.compile.zip(right.compile)

  final case class Product[F[_, _], I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left: FreeScanK.Aux[F, I1, O1, S1],
    right: FreeScanK.Aux[F, I2, O2, S2],
  ) extends FreeScanK[F, (I1, I2), (O1, O2)]:
    type State = Tuple.Concat[S1, S2]
    def compile(using Interpreter[F]): Scan.Aux[(I1, I2), (O1, O2), State] =
      val a     = left.compile
      val b     = right.compile
      val sizeA = a.initial.productArity
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

  final case class First[F[_, _], I, O, C, S <: Tuple](
    src: FreeScanK.Aux[F, I, O, S]
  ) extends FreeScanK[F, (I, C), (O, C)]:
    type State = Tuple.Concat[S, Tuple1[Option[C]]]
    def compile(using Interpreter[F]): Scan.Aux[(I, C), (O, C), State] =
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

  final case class Second[F[_, _], I, O, C, S <: Tuple](
    src: FreeScanK.Aux[F, I, O, S]
  ) extends FreeScanK[F, (C, I), (C, O)]:
    type State = Tuple.Concat[Tuple1[Option[C]], S]
    def compile(using Interpreter[F]): Scan.Aux[(C, I), (C, O), State] =
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

  final case class LeftChoice[F[_, _], I, O, C, S <: Tuple](
    src: FreeScanK.Aux[F, I, O, S]
  ) extends FreeScanK[F, Either[I, C], Either[O, C]]:
    type State = S
    def compile(using Interpreter[F]): Scan.Aux[Either[I, C], Either[O, C], S] =
      val a = src.compile
      Scan.statefulTuple[Either[I, C], Either[O, C], a.State](a.initial) { (s, in) =>
        in match
          case Left(i)  =>
            val (s2, out) = a.step(s, i)
            (s2, out.map(Left(_)))
          case Right(c) => (s, Chunk.single(Right(c)))
      }(s => a.done(s).map(Left(_)))

  final case class RightChoice[F[_, _], I, O, C, S <: Tuple](
    src: FreeScanK.Aux[F, I, O, S]
  ) extends FreeScanK[F, Either[C, I], Either[C, O]]:
    type State = S
    def compile(using Interpreter[F]): Scan.Aux[Either[C, I], Either[C, O], S] =
      val a = src.compile
      Scan.statefulTuple[Either[C, I], Either[C, O], a.State](a.initial) { (s, in) =>
        in match
          case Right(i) =>
            val (s2, out) = a.step(s, i)
            (s2, out.map(Right(_)))
          case Left(c)  => (s, Chunk.single(Left(c)))
      }(s => a.done(s).map(Right(_)))

  final case class PlusPlus[F[_, _], I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left: FreeScanK.Aux[F, I1, O1, S1],
    right: FreeScanK.Aux[F, I2, O2, S2],
  ) extends FreeScanK[F, Either[I1, I2], Either[O1, O2]]:
    type State = Tuple.Concat[S1, S2]
    def compile(using Interpreter[F]): Scan.Aux[Either[I1, I2], Either[O1, O2], State] =
      val a     = left.compile
      val b     = right.compile
      val sizeA = a.initial.productArity
      Scan.statefulTuple[Either[I1, I2], Either[O1, O2], Tuple.Concat[a.State, b.State]](a.initial ++ b.initial) { (st, in) =>
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

  final case class FanIn[F[_, _], I1, O, S1 <: Tuple, I2, S2 <: Tuple](
    left: FreeScanK.Aux[F, I1, O, S1],
    right: FreeScanK.Aux[F, I2, O, S2],
  ) extends FreeScanK[F, Either[I1, I2], O]:
    type State = Tuple.Concat[S1, S2]
    def compile(using Interpreter[F]): Scan.Aux[Either[I1, I2], O, State] =
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

  // Syntax
  extension [F[_, _], I, O, S <: Tuple](self: FreeScanK.Aux[F, I, O, S])
    transparent inline def >>>[O2, S2 <: Tuple](that: FreeScanK.Aux[F, O, O2, S2]): FreeScanK.Aux[F, I, O2, Tuple.Concat[S, S2]]      =
      AndThen(self, that)
    transparent inline def &&&[O2, S2 <: Tuple](that: FreeScanK.Aux[F, I, O2, S2]): FreeScanK.Aux[F, I, (O, O2), Tuple.Concat[S, S2]] =
      Zip(self, that)
    transparent inline def ***[I2, O2, S2 <: Tuple](
      that: FreeScanK.Aux[F, I2, O2, S2]
    ): FreeScanK.Aux[F, (I, I2), (O, O2), Tuple.Concat[S, S2]] = Product(self, that)
    transparent inline def first[C]: FreeScanK.Aux[F, (I, C), (O, C), Tuple.Concat[S, Tuple1[Option[C]]]]                             = First(self)
    transparent inline def second[C]: FreeScanK.Aux[F, (C, I), (C, O), Tuple.Concat[Tuple1[Option[C]], S]]                            = Second(self)
    transparent inline def left[C]: FreeScanK.Aux[F, Either[I, C], Either[O, C], S]                                                   = LeftChoice(self)
    transparent inline def right[C]: FreeScanK.Aux[F, Either[C, I], Either[C, O], S]                                                  = RightChoice(self)
    transparent inline def +++[I2, O2, S2 <: Tuple](
      that: FreeScanK.Aux[F, I2, O2, S2]
    ): FreeScanK.Aux[F, Either[I, I2], Either[O, O2], Tuple.Concat[S, S2]] = PlusPlus(self, that)
    transparent inline def |||[I2, S2 <: Tuple](
      that: FreeScanK.Aux[F, I2, O, S2]
    ): FreeScanK.Aux[F, Either[I, I2], O, Tuple.Concat[S, S2]] = FanIn(self, that)

  def id[F[_, _], A]: Aux[F, A, A, EmptyTuple]                                        = Id[F, A]()
  def op[F[_, _], I, O, S <: Tuple](fab: F[I, O] { type State = S }): Aux[F, I, O, S] = Op(fab)

  // State schema helpers (placeholder for future composition of leaf schemas)

  // Optimizer (structure-only)
  private def optimize[F[_, _], I, O, S <: Tuple](fs: FreeScanK.Aux[F, I, O, S]): FreeScanK.Aux[F, I, O, S] =
    fs match
      case AndThen(Id(), r) => optimize(r).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case AndThen(l, Id()) => optimize(l).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case AndThen(l, r)    => AndThen(optimize(l), optimize(r)).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case Zip(l, r)        => Zip(optimize(l), optimize(r)).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case Product(l, r)    => Product(optimize(l), optimize(r)).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case First(s)         => First(optimize(s)).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case Second(s)        => Second(optimize(s)).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case LeftChoice(s)    => LeftChoice(optimize(s)).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case RightChoice(s)   => RightChoice(optimize(s)).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case PlusPlus(l, r)   => PlusPlus(optimize(l), optimize(r)).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case FanIn(l, r)      => FanIn(optimize(l), optimize(r)).asInstanceOf[FreeScanK.Aux[F, I, O, S]]
      case other            => other
