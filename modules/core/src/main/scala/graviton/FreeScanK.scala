package graviton

import zio.*
import zio.schema.Schema

import scala.<:<

/**
 * A pure-data Free Arrow for scans, parameterized by a domain DSL F.
 * Leaves are F[A, B] values that must encode their State type via a type member.
 * No lambdas or closures are stored in this structure; interpretation happens via Interpreter.
 */
sealed trait FreeScanK[F[_, _], -I, +O]:
  type State <: Tuple
  type Size <: Int = State match {
    case NonEmptyTuple => Tuple.Size[State]
    case _             =>
      State <:< scala.Product match
        case true  => 1
        case false => 0
  }
  inline def size: Size = scala.compiletime.summonInline[Size]
  type Types
  def compile(using FreeScanK.Interpreter[F]): Scan.Aux[I, O, State]
  inline def compileOptimized(using FreeScanK.Interpreter[F]): Scan.Aux[I, O, State] =
    FreeScanK.optimize(this).compile
  def labelsFromType(using FreeScanK.Interpreter[F]): Chunk[String]

object FreeScanK:
  type Aux[F[_, _], -I, +O, S <: Tuple] = FreeScanK[F, I, O] { type State = S }

  // Interpreter from DSL to concrete Scan
  trait Interpreter[F[_, _]]:
    type State
    def toScan[I, O, S <: Tuple](op: F[I, O] { type State = S }): Scan.Aux[I, O, S]
    def stateSchema[I, O, S <: Tuple](op: F[I, O] { type State = S }): Schema[S]
    def stateLabels[I, O, S <: Tuple](op: F[I, O] { type State = S }): Chunk[String]

  // Constructors
  final case class Id[F[_, _], A]() extends FreeScanK[F, A, A]:
    type State = EmptyTuple
    type Types = Any
    inline def compile(using Interpreter[F]): Scan.Aux[A, A, EmptyTuple] = Scan.identity[A]
    override def labelsFromType(using Interpreter[F]): Chunk[String]     = Chunk.empty

  final case class Op[F[
    _,
    _,
  ] <: { type State <: Tuple }, I, O, S <: Tuple](
    fab: F[I, O] { type State = S }
  ) extends FreeScanK[F, I, O]:
    type State = S

    type Types = Any
    def compile(using i: Interpreter[F]): Scan.Aux[I, O, S]             = i.toScan(fab)
    inline def schema(using i: Interpreter[F]): Schema[S]               = i.stateSchema(fab)
    inline def labels(using i: Interpreter[F]): Chunk[String]           = i.stateLabels(fab)
    override def labelsFromType(using i: Interpreter[F]): Chunk[String] = i.stateLabels(fab)

  final case class AndThen[F[_, _], A, B, C, S1 <: Tuple, S2 <: Tuple](
    left: FreeScanK.Aux[F, A, B, S1],
    right: FreeScanK.Aux[F, B, C, S2],
  ) extends FreeScanK[F, A, C]:
    type State = Tuple.Concat[S1, S2]
    type Types = Any
    def compile(using Interpreter[F]): Scan.Aux[A, C, State]         = left.compile.andThen(right.compile)
    override def labelsFromType(using Interpreter[F]): Chunk[String] = left.labelsFromType ++ right.labelsFromType

  def andThen[F[_, _], A, B, C, S1 <: Tuple, S2 <: Tuple](
    left: FreeScanK.Aux[F, A, B, S1],
    right: FreeScanK.Aux[F, B, C, S2],
  ): FreeScanK.Aux[F, A, C, Tuple.Concat[S1, S2]] =
    AndThen(left, right)

  final case class Zip[F[_, _], I, O1, O2, S1 <: Tuple, S2 <: Tuple](
    left: FreeScanK.Aux[F, I, O1, S1],
    right: FreeScanK.Aux[F, I, O2, S2],
  ) extends FreeScanK[F, I, (O1, O2)]:
    type State = Tuple.Concat[S1, S2]
    type Types = Any
    inline def compile(using Interpreter[F]): Scan.Aux[I, (O1, O2), State]  = left.compile.zip(right.compile)
    inline override def labelsFromType(using Interpreter[F]): Chunk[String] = left.labelsFromType ++ right.labelsFromType

  end Zip

  def zip[F[_, _], I, O1, O2, S1 <: Tuple, S2 <: Tuple](
    left: FreeScanK.Aux[F, I, O1, S1],
    right: FreeScanK.Aux[F, I, O2, S2],
  ): FreeScanK.Aux[F, I, (O1, O2), Tuple.Concat[S1, S2]] =
    Zip(left, right)

  // Typeclass to prove that Tuple.Concat[A, B] can be split back into (A, B)
  trait SplitConcat[A <: Tuple, B <: Tuple, AB <: Tuple]:
    def split(ab: AB): (A, B)

  object SplitConcat:

    given splitEmpty[A <: Tuple]: SplitConcat[A, EmptyTuple, A] with
      def split(ab: A): (A, EmptyTuple) = (ab, EmptyTuple)

    given splitNilRight[B <: Tuple]: SplitConcat[EmptyTuple, B, B] with
      def split(ab: B): (EmptyTuple, B) = (EmptyTuple, ab)

    given splitCons[AHead, ATail <: Tuple, B <: Tuple, ABTail <: Tuple](using tail: SplitConcat[ATail, B, ABTail]): SplitConcat[
      AHead *: ATail,
      B,
      AHead *: ABTail,
    ] with
      def split(ab: AHead *: ABTail): (AHead *: ATail, B) =
        val (aTail, b) = tail.split(ab.tail)
        (ab.head *: aTail, b)
  end SplitConcat

  final case class Product[F[_, _], I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left: FreeScanK.Aux[F, I1, O1, S1],
    right: FreeScanK.Aux[F, I2, O2, S2],
  )(using split: SplitConcat[S1, S2, Tuple.Concat[S1, S2]])
      extends FreeScanK[F, (I1, I2), (O1, O2)]:
    type State = Tuple.Concat[S1, S2]
    type Types = Any

    def compile(using i: Interpreter[F]): Scan.Aux[(I1, I2), (O1, O2), State] =
      val a = left.compile(using i)
      val b = right.compile(using i)
      type S1 = a.State
      type S2 = b.State
      type S  = Tuple.Concat[S1, S2]

      Scan.statefulTuple[(I1, I2), (O1, O2), S](a.initial ++ b.initial) { (st, in) =>
        val (s1, s2)  = split.split(st)
        val (i1, i2)  = in
        val (s1b, o1) = a.step(s1, i1)
        val (s2b, o2) = b.step(s2, i2)
        ((s1b ++ s2b).asInstanceOf[S], o1.zip(o2))
      } { st =>
        val (s1, s2) = split.split(st.asInstanceOf[S])
        a.done(s1).zip(b.done(s2))
      }

    override def labelsFromType(using Interpreter[F]): Chunk[String] = left.labelsFromType ++ right.labelsFromType

  end Product

  def product[F[_, _], I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left: FreeScanK.Aux[F, I1, O1, S1],
    right: FreeScanK.Aux[F, I2, O2, S2],
  )(using split: SplitConcat[S1, S2, Tuple.Concat[S1, S2]]): FreeScanK.Aux[F, (I1, I2), (O1, O2), Tuple.Concat[S1, S2]] =
    Product(left, right)

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
    override def labelsFromType(using Interpreter[F]): Chunk[String]   = src.labelsFromType

  def first[F[_, _], I, O, C, S <: Tuple](
    src: FreeScanK.Aux[F, I, O, S]
  ): FreeScanK.Aux[F, (I, C), (O, C), Tuple.Concat[S, Tuple1[Option[C]]]] =
    First(src)

  final case class Second[F[_, _], I, O, C, S <: Tuple](
    src: FreeScanK.Aux[F, I, O, S]
  ) extends FreeScanK[F, (C, I), (C, O)]:
    type State = Option[C] *: S
    def compile(using Interpreter[F]): Scan.Aux[(C, I), (C, O), State] =
      val a     = src.compile
      val sizeA = 1 // Tuple1[Option[C]]
      Scan.statefulTuple[(C, I), (C, O), Option[C] *: a.State](Option.empty[C] *: a.initial) { (st, in) =>
        val sA       = st.drop(sizeA).asInstanceOf[a.State]
        val (c, i)   = in
        val (sAb, o) = a.step(sA, i)
        ((Some(c) *: sAb).asInstanceOf[Option[C] *: a.State], o.map(o2 => (c, o2)))
      } { st =>
        val lastCOpt = st.head
        val sA       = st.tail
        lastCOpt match
          case Some(c) => a.done(sA).map(o => (c, o))
          case None    => Chunk.empty
      }
    override def labelsFromType(using Interpreter[F]): Chunk[String]   = src.labelsFromType

  end Second

  def second[F[_, _], I, O, C, S <: Tuple](
    src: FreeScanK.Aux[F, I, O, S]
  ): FreeScanK.Aux[F, (C, I), (C, O), Option[C] *: S] =
    Second(src)

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
    override def labelsFromType(using Interpreter[F]): Chunk[String]           = src.labelsFromType

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
    override def labelsFromType(using Interpreter[F]): Chunk[String]           = src.labelsFromType
  end RightChoice

  def rightChoice[F[_, _], I, O, C, S <: Tuple](
    src: FreeScanK.Aux[F, I, O, S]
  ): FreeScanK.Aux[F, Either[C, I], Either[C, O], S] =
    RightChoice(src)

  def plusPlus[F[_, _], I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left: FreeScanK.Aux[F, I1, O1, S1],
    right: FreeScanK.Aux[F, I2, O2, S2],
  ): FreeScanK.Aux[F, Either[I1, I2], Either[O1, O2], Tuple.Concat[S1, S2]] =
    PlusPlus(left, right)

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
    override def labelsFromType(using Interpreter[F]): Chunk[String]                   = left.labelsFromType ++ right.labelsFromType
  end PlusPlus

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
    override def labelsFromType(using Interpreter[F]): Chunk[String]      = left.labelsFromType ++ right.labelsFromType

  end FanIn

  def fanIn[F[_, _], I1, O, S1 <: Tuple, I2, S2 <: Tuple](
    left: FreeScanK.Aux[F, I1, O, S1],
    right: FreeScanK.Aux[F, I2, O, S2],
  ): FreeScanK.Aux[F, Either[I1, I2], O, Tuple.Concat[S1, S2]] =
    FanIn(left, right)

  // Syntax
  extension [F[_, _], I, O, S <: Tuple](self: FreeScanK.Aux[F, I, O, S])
    transparent inline def >>>[O2, S2 <: Tuple](
      inline that: FreeScanK.Aux[F, O, O2, S2]
    ): FreeScanK.Aux[F, I, O2, Tuple.Concat[S, S2]] =
      AndThen(self, that)
    transparent inline def &&&[O2, S2 <: Tuple](
      inline that: FreeScanK.Aux[F, I, O2, S2]
    ): FreeScanK.Aux[F, I, (O, O2), Tuple.Concat[S, S2]] =
      Zip(self, that)
    transparent inline def ***[I2, O2, S2 <: Tuple](
      inline that: FreeScanK.Aux[F, I2, O2, S2]
    )(using split: SplitConcat[S, S2, Tuple.Concat[S, S2]]): FreeScanK.Aux[F, (I, I2), (O, O2), Tuple.Concat[S, S2]] = Product(self, that)
    transparent inline def first[C]: FreeScanK.Aux[F, (I, C), (O, C), Tuple.Concat[S, Tuple1[Option[C]]]] = First(self)
    transparent inline def second[C]: FreeScanK.Aux[F, (C, I), (C, O), Option[C] *: S]                    = Second(self)
    transparent inline def left[C]: FreeScanK.Aux[F, Either[I, C], Either[O, C], S]                       = LeftChoice(self)
    transparent inline def right[C]: FreeScanK.Aux[F, Either[C, I], Either[C, O], S]                      = RightChoice(self)
    transparent inline def +++[I2, O2, S2 <: Tuple](
      that: FreeScanK.Aux[F, I2, O2, S2]
    ): FreeScanK.Aux[F, Either[I, I2], Either[O, O2], Tuple.Concat[S, S2]] = PlusPlus(self, that)
    transparent inline def |||[I2, S2 <: Tuple](
      that: FreeScanK.Aux[F, I2, O, S2]
    ): FreeScanK.Aux[F, Either[I, I2], O, Tuple.Concat[S, S2]] = FanIn(self, that)
  end extension

  inline def id[F[_, _], A]: Aux[F, A, A, EmptyTuple] = Id[F, A]()
  inline def op[F[_, _] <: { type State <: Tuple }, I, O, S <: Tuple](
    fab: F[I, O] { type State = S }
  ): Aux[F, I, O, S] =
    Op(fab)

  // Optimizer (no-op for now to keep types simple and avoid inline recursion)
  private def optimize[F[_, _], I, O, S <: Tuple](
    fs: FreeScanK.Aux[F, I, O, S]
  ): FreeScanK.Aux[F, I, O, S] =
    fs match
      case AndThen(Id(), r) => optimize(r.asInstanceOf[FreeScanK.Aux[F, I, O, S]])
      case AndThen(l, Id()) => optimize(l.asInstanceOf[FreeScanK.Aux[F, I, O, S]])
      case Zip(l, r)        => optimize(Zip(l, r).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
      case First(s)         => optimize(First(s).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
      case Second(s)        => optimize(Second(s).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
      case LeftChoice(s)    => optimize(LeftChoice(s).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
      case RightChoice(s)   => optimize(RightChoice(s).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
      case PlusPlus(l, r)   => optimize(PlusPlus(l, r).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
      case FanIn(l, r)      => optimize(FanIn(l, r).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
      case other            => other
