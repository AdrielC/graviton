package graviton

// import zio.*
// import zio.schema.Schema

// import scala.<:<
// import cats.Eval
// import cats.implicits.*

// /**
//  * A pure-data Free Arrow for scans, parameterized by a domain DSL F.
//  * Leaves are F[A, B] values that must encode their State type via a type member.
//  * No lambdas or closures are stored in this structure; interpretation happens via Interpreter.
//  */
// sealed trait FreeScanK[:=>:[-?, +?], -I, +O]:
//   type S <: Matchable
//   type State       = Scan.ToState[S]
//   type Size <: Int = State match {
//     case NonEmptyTuple => Tuple.Size[State]
//     case _             =>
//       State <:< scala.Product match
//         case true  => 1
//         case false => 0
//   }
//   inline def size: Size = scala.compiletime.summonInline[Size]
//   type Types
//   def compile(using FreeScanK.Interpreter[:=>:]): Scan.Aux[I, O, Scan.ToState[S]]
//   inline def compileOptimized(using FreeScanK.Interpreter[:=>:]): Scan.Aux[I, O, Scan.ToState[S]] =
//     FreeScanK.optimize(this).compile
//   def labelsFromType(using FreeScanK.Interpreter[:=>:]): Chunk[String]

object FreeScanK:
  // type Aux[:=>:[-?, +?], -I, +O, SS <: Matchable] = FreeScanK[:=>:, I, O] { type S = SS }

  // Interpreter from DSL to concrete Scan
  trait Interpreter[:=>:[-?, +?]]:
    type State
    def toScan[I, O, S <: Matchable](op: I :=>: O): Scan.Aux[I, O, Scan.ToState[S]]
    def stateSchema[I, O, S <: Matchable](op: I :=>: O): zio.schema.Schema[Scan.ToState[S]]
    def stateLabels[I, O, S <: Matchable](op: I :=>: O): zio.Chunk[String]

//   // Constructors
//   final case class Id[F[_, _], A]() extends FreeScanK[F, A, A]:
//     type S = EmptyTuple
//     type Types = Any
//     inline def compile(using Interpreter[F]): Scan.Aux[A, A, S]   = Scan.identity[A]
//     override def labelsFromType(using Interpreter[F]): Chunk[String] = Chunk.empty

//   final case class Op[F[
//     _,
//     _,
//   ] <: { type State <: Tuple }, I, O, S <: Tuple](
//     fab: F[I, O] { type State = S }
//   ) extends FreeScanK[F, I, O]:

//     type Types = Any
//     override def compile(using i: Interpreter[F]): Scan.Aux[I, O, Scan.ToState[S]]             = i.toScan(fab)
//     inline def schema(using i: Interpreter[F]): Schema[S]               = i.stateSchema(fab)
//     inline def labels(using i: Interpreter[F]): Chunk[String]           = i.stateLabels(fab)
//     override def labelsFromType(using i: Interpreter[F]): Chunk[String] = i.stateLabels(fab)

//   final case class AndThen[F[_, _], A, B, C, S1 <: Matchable, S2 <: Matchable](
//     left: FreeScanK.Aux[F, A, B, S1],
//     right: FreeScanK.Aux[F, B, C, S2],
//   ) extends FreeScanK[F, A, C]:
//     type S = Tuple.Concat[left.State, right.State]
//     type Types = Any
//     def compile(using Interpreter[F]): Scan.Aux[A, C, Tuple.Concat[Scan.ToState[left.State], Scan.ToState[right.State]]]         = 
//       left.compile.andThen(right.compile)
//     override def labelsFromType(using Interpreter[F]): Chunk[String] = left.labelsFromType ++ right.labelsFromType

//   def andThen[F[_, _], A, B, C, S1 <: Matchable, S2 <: Matchable](
//     left: FreeScanK.Aux[F, A, B, S1],
//     right: FreeScanK.Aux[F, B, C, S2],
//   ): FreeScanK.Aux[F, A, C, Tuple.Concat[Scan.ToState[S1], Scan.ToState[S2]]] =
//     AndThen(left, right)
//       .asInstanceOf[FreeScanK.Aux[F, A, C, Tuple.Concat[Scan.ToState[S1], Scan.ToState[S2]]]]

//   final case class Zip[F[_, _], I, O1, O2, S1 <: Matchable, S2 <: Matchable](
//     left: FreeScanK.Aux[F, I, O1, S1],
//     right: FreeScanK.Aux[F, I, O2, S2],
//   ) extends FreeScanK[F, I, (O1, O2)]:

//     override type S = Tuple.Concat[Scan.ToState[S1], Scan.ToState[S2]]

//     type Types = Any
//     inline def compile(using Interpreter[F]): Scan.Aux[I, (O1, O2), Scan.ToState[S]]      = left.compile.zip(right.compile)
//     inline override def labelsFromType(using Interpreter[F]): Chunk[String] = left.labelsFromType ++ right.labelsFromType

//   end Zip

//   def zip[F[_, _], I, O1, O2, S1 <: Tuple, S2 <: Tuple](
//     left: FreeScanK.Aux[F, I, O1, S1],
//     right: FreeScanK.Aux[F, I, O2, S2],
//   ): FreeScanK.Aux[F, I, (O1, O2), Tuple.Concat[S1, S2]] =
//     Zip(left, right)
//       .asInstanceOf[FreeScanK.Aux[F, I, (O1, O2), Tuple.Concat[S1, S2]]]

//   // Typeclass to prove that Tuple.Concat[A, B] can be split back into (A, B)
//   trait SplitConcat[A <: Matchable, B <: Matchable, AB <: Tuple & Matchable]:
//     def split(ab: AB): (A, B)

//   object SplitConcat:

//     given splitEmpty: [A <: Matchable & Tuple] => SplitConcat[A, EmptyTuple, A]:
//       def split(ab: A): (A, EmptyTuple) = (ab, EmptyTuple)

//     given splitNilRight: [B <: Matchable & Tuple] => SplitConcat[EmptyTuple, B, B]:
//       def split(ab: B): (EmptyTuple, B) = (EmptyTuple, ab)

//     given splitCons: [AHead, ATail <: Matchable & Tuple, B <: Matchable, ABTail <: Matchable & Tuple]
//       => (tail: SplitConcat[ATail, B, ABTail])
//         => SplitConcat[
//           AHead *: ATail,
//           B,
//           AHead *: ABTail,
//         ]:
//       def split(ab: AHead *: ABTail): (AHead *: ATail, B) =
//         val (aTail, b) = tail.split(ab.tail)
//         (ab.head *: aTail, b)

//   end SplitConcat

//   final case class Product[F[_, _], I1, O1, S1 <: Matchable, I2, O2, S2 <: Matchable](
//     left: FreeScanK.Aux[F, I1, O1, S1],
//     right: FreeScanK.Aux[F, I2, O2, S2],
//   )(using split: SplitConcat[left.State, right.State, Tuple.Concat[Scan.ToState[S1], Scan.ToState[S2]]])
//       extends FreeScanK[F, (I1, I2), (O1, O2)]:
//     type S     = Tuple.Concat[left.State, right.State]
//     type Types = Any

//     def compile(using i: Interpreter[F]): Scan.Aux[(I1, I2), (O1, O2), S] =
//       val a = left.compile(using i)
//       val b = right.compile(using i)
//       Scan.statefulTuple[(I1, I2), (O1, O2), S]((a.initial ++ b.initial)) { (st: S, in: (I1, I2)) =>
//         val (s1, s2)  = split.split(st)
//         val (i1, i2)  = in
//         val (s1b, o1) = a.step(s1, i1)
//         val (s2b, o2) = b.step(s2, i2)
//         ((s1b ++ s2b), o1.zip(o2))
//       } { (st: S) =>
//         val (s1, s2) = split.split(st)
//         a.done(s1).zip(b.done(s2))
//       }

//     override def labelsFromType(using Interpreter[F]): Chunk[String] = left.labelsFromType ++ right.labelsFromType

//   end Product

//   def product[F[_, _], I1, O1, S1 <: Matchable, I2, O2, S2 <: Matchable](
//     left: FreeScanK.Aux[F, I1, O1, S1],
//     right: FreeScanK.Aux[F, I2, O2, S2],
//   )(
//     using split: SplitConcat[left.State, right.State, Tuple.Concat[Scan.ToState[S1], Scan.ToState[S2]]]
//   ): FreeScanK.Aux[F, (I1, I2), (O1, O2), Tuple.Concat[left.State, right.State]] =
//     FreeScanK.Product(left, right).asInstanceOf[FreeScanK.Aux[F, (I1, I2), (O1, O2), Tuple.Concat[left.State, right.State]]]

//   final case class First[F[_, _], I, O, C, SS <: Matchable](
//     src: FreeScanK.Aux[F, I, O, SS]
//   ) extends FreeScanK[F, (I, C), (O, C)]:

//     override type S = Tuple.Concat[src.State, Tuple1[Option[C]]]

//     def compile(using Interpreter[F]): Scan.Aux[(I, C), (O, C), Tuple.Concat[Scan.ToState[SS], Tuple1[Option[C]]]] =
//       val a     = src.compile
//       val sizeA = a.initial.productArity
//       Scan.statefulTuple[(I, C), (O, C), Tuple.Concat[Scan.ToState[SS], Tuple1[Option[C]]]](
//         (a.initialState, Eval.now(Tuple1(None))).mapN(_ ++ _)
//       ) { (st, in) =>
//         val (sA, sC)  = st.splitAt(sizeA)
//         val (i, c)    = in
//         val (sAb, oA) = a.step(Scan.toState(sA).asInstanceOf[a.State], i)
//         ((sAb ++ Tuple1(Some(c))).asInstanceOf[Tuple.Concat[Scan.ToState[SS], Tuple1[Option[C]]]], oA.map(o => (o, c)))
//       } { st =>
//         val (sA, sC) = st.splitAt(sizeA)
//         a.done(Scan.toState(sA).asInstanceOf[a.State]).flatMap(o => Scan.toState(sC).asInstanceOf[Tuple1[Option[C]]]._1.map(c => o -> c))
//       }
//     override def labelsFromType(using Interpreter[F]): Chunk[String]                                               = src.labelsFromType

//   def first[F[_, _], I, O, C, S <: Tuple](
//     src: FreeScanK.Aux[F, I, O, S]
//   ): FreeScanK.Aux[F, (I, C), (O, C), Tuple.Concat[S, Tuple1[Option[C]]]] =
//     First(src)
//       .asInstanceOf[FreeScanK.Aux[F, (I, C), (O, C), Tuple.Concat[S, Tuple1[Option[C]]]]]
//   final case class Second[F[_, _], I, O, C, SS <: Matchable](
//     src: FreeScanK.Aux[F, I, O, SS]
//   ) extends FreeScanK[F, (C, I), (C, O)]:
//     override type S = Option[C] *: src.State
//     def compile(using Interpreter[F]): Scan.Aux[(C, I), (C, O), S]   =
//       val a     = src.compile
//       val sizeA = 1 // Tuple1[Option[C]]
//       Scan.statefulTuple[(C, I), (C, O), S](a.initialState.map(s => (Option.empty[C] *: s))) { case ((st: S), (in: (C, I))) =>
//         val (sA, sC) = st.splitAt(sizeA)
//         val (c, i)   = in
//         val (sAb, o) = a.step(sA.asInstanceOf[a.State], i)
//         ((Some(c) *: sAb.asInstanceOf[src.State]), o.map(o2 => (c, o2)))
//       } { (st: S) =>
//         val (lastCOpt, sA) = st.splitAt(sizeA)
//         val lastCOptOpt    = lastCOpt.asInstanceOf[Option[C]]

//         lastCOptOpt match
//           case Some(c: C) => a.done(sA.asInstanceOf[a.State]).map((o: O) => (c, o))
//           case None       => Chunk.empty[(C, O)]
//       }
//     override def labelsFromType(using Interpreter[F]): Chunk[String] = src.labelsFromType

//   end Second

//   inline def second[F[_, _], I, O, C, S <: Matchable & Tuple](
//     src: FreeScanK.Aux[F, I, O, S]
//   ) =
//     Second[F, I, O, C, S](src)

//   final case class LeftChoice[F[_, _], I, O, C, S <: Matchable](
//     src: FreeScanK.Aux[F, I, O, S]
//   ) extends FreeScanK[F, Either[I, C], Either[O, C]]:
//     type State = S
//     def compile(using Interpreter[F]): Scan.Aux[Either[I, C], Either[O, C], S] =
//       val a = src.compile
//       Scan.statefulTuple(a._initial.value) { (st: S, in: Either[I, C]) =>
//         in match
//           case Left(i)  =>
//             val (s2, out) = a.step(Scan.toState(st).asInstanceOf[a.State], i)
//             ((Scan.toState(s2).asInstanceOf[S]), out.map(Left(_)))
//           case Right(c) => (Scan.toState(st).asInstanceOf[S], Chunk.single(Right(c)))
//       }(s => a.done(s.asInstanceOf[a.State]).map(Left(_)))
//     override def labelsFromType(using Interpreter[F]): Chunk[String]           = src.labelsFromType

//   final case class RightChoice[F[_, _], I, O, C, S <: Matchable](
//     src: FreeScanK.Aux[F, I, O, S]
//   ) extends FreeScanK[F, Either[C, I], Either[C, O]]:
//     type State = S
//     def compile(using Interpreter[F]): Scan.Aux[Either[C, I], Either[C, O], S] =
//       val a = src.compile
//       Scan.statefulTuple(a._initial.value) { (st: S, in: Either[C, I]) =>
//         in match
//           case Right(i) =>
//             val (s2, out) = a.step(Scan.toState(st).asInstanceOf[a.State], i)
//             (Scan.toState(s2).asInstanceOf[S], out.map(Right(_)))
//           case Left(c)  => (Scan.toState(st).asInstanceOf[S], Chunk.single(Left(c)))
//       }(s => a.done(Scan.toState(s)).map(Right(_)))
//     override def labelsFromType(using Interpreter[F]): Chunk[String]           = src.labelsFromType
//   end RightChoice

//   def rightChoice[F[_, _], I, O, C, S <: Matchable](
//     src: FreeScanK.Aux[F, I, O, S]
//   ): FreeScanK.Aux[F, Either[C, I], Either[C, O], S] =
//     RightChoice(src)

//   transparent inline def plusPlus[F[_, _], I1, O1, I2, O2](
//     left: FreeScanK[F, I1, O1],
//     right: FreeScanK[F, I2, O2],
//   ): FreeScanK[F, Either[I1, I2], Either[O1, O2]] =
//     PlusPlus[F, I1, O1, I2, O2](left, right)

//   final case class PlusPlus[F[_, _], I1, O1, I2, O2](
//     left: FreeScanK[F, I1, O1],
//     right: FreeScanK[F, I2, O2],
//   ) extends FreeScanK[F, Either[I1, I2], Either[O1, O2]]:
//     final type State = Tuple.Concat[left.State, right.State]
//     def compile(using Interpreter[F]): Scan.Aux[Either[I1, I2], Either[O1, O2], State] =
//       val a     = left.compile
//       val b     = right.compile
//       val sizeA = a.initial.productArity
//       Scan.statefulTuple[Either[I1, I2], Either[O1, O2], Tuple.Concat[a.State, b.State]](a.initial ++ b.initial) { (st, in) =>
//         val s1 = st.take(sizeA).asInstanceOf[a.State]
//         val s2 = st.drop(sizeA).asInstanceOf[b.State]
//         in match
//           case Left(i1)  =>
//             val (s1b, o1) = a.step(s1, i1)
//             ((s1b ++ s2).asInstanceOf[Tuple.Concat[a.State, b.State]], o1.map(Left(_)))
//           case Right(i2) =>
//             val (s2b, o2) = b.step(s2, i2)
//             ((s1 ++ s2b).asInstanceOf[Tuple.Concat[a.State, b.State]], o2.map(Right(_)))
//       } { st =>
//         val s1 = st.take(sizeA).asInstanceOf[a.State]
//         val s2 = st.drop(sizeA).asInstanceOf[b.State]
//         a.done(s1).map(Left(_)) ++ b.done(s2).map(Right(_))
//       }
//     override def labelsFromType(using Interpreter[F]): Chunk[String]                   = left.labelsFromType ++ right.labelsFromType
//   end PlusPlus

//   final case class FanIn[F[_, _], I1, O, S1 <: Matchable, I2, S2 <: Matchable](
//     left: FreeScanK.Aux[F, I1, O, S1],
//     right: FreeScanK.Aux[F, I2, O, S2],
//   ) extends FreeScanK[F, Either[I1, I2], O]:
//     final type State = Tuple.Concat[left.State, right.State]
//     def compile(using Interpreter[F]): Scan.Aux[Either[I1, I2], O, State] =
//       val a     = left.compile
//       val b     = right.compile
//       val sizeA = a.initial.productArity
//       Scan.statefulTuple[Either[I1, I2], O, Tuple.Concat[left.State, right.State]](
//         a.initial.asInstanceOf[left.State] ++ b.initial.asInstanceOf[right.State]
//       ) { (st, in) =>
//         val s1 = st.take(sizeA).asInstanceOf[a.State]
//         val s2 = st.drop(sizeA).asInstanceOf[b.State]
//         in match
//           case Left(i1)  =>
//             val (s1b, o1) = a.step(s1, i1)
//             ((s1b ++ s2).asInstanceOf[Tuple.Concat[left.State, right.State]], o1)
//           case Right(i2) =>
//             val (s2b, o2) = b.step(s2, i2)
//             ((s1 ++ s2b).asInstanceOf[Tuple.Concat[left.State, right.State]], o2)
//       } { st =>
//         val s1 = st.take(sizeA).asInstanceOf[a.State]
//         val s2 = st.drop(sizeA).asInstanceOf[b.State]
//         (a.done(s1) ++ b.done(s2))
//       }
//     override def labelsFromType(using Interpreter[F]): Chunk[String]      = left.labelsFromType ++ right.labelsFromType

//   end FanIn

//   def fanIn[F[_, _], I1, O, S1 <: Tuple, I2, S2 <: Tuple](
//     left: FreeScanK.Aux[F, I1, O, S1],
//     right: FreeScanK.Aux[F, I2, O, S2],
//   ): FreeScanK[F, Either[I1, I2], O] =
//     FanIn(left, right)

//   // Syntax
//   extension [F[_, _], I, O, S <: Matchable](self: FreeScanK.Aux[F, I, O, S])
//     transparent inline def >>>[O2, S2 <: Matchable](
//       inline that: FreeScanK.Aux[F, O, O2, S2]
//     ): FreeScanK[F, I, O2] =
//       AndThen(self, that)
//     transparent inline def &&&[O2, S2 <: Matchable](
//       inline that: FreeScanK.Aux[F, I, O2, S2]
//     ): FreeScanK[F, I, (O, O2)] =
//       Zip(self, that)
//     transparent inline def ***[I2, O2, S2 <: Matchable](
//       that: FreeScanK.Aux[F, I2, O2, S2]
//     )(using split: SplitConcat[self.State, that.State, Tuple.Concat[Scan.ToState[S], Scan.ToState[S2]]]): FreeScanK[F, (I, I2), (O, O2)] =
//       Product(self, that)(using split)
//     transparent inline def first[C]: FreeScanK[F, (I, C), (O, C)]             = First[F, I, O, C, S](self)
//     transparent inline def second[C]: FreeScanK[F, (C, I), (C, O)]            = Second[F, I, O, C, S](self)
//     transparent inline def left[C]: FreeScanK[F, Either[I, C], Either[O, C]]  = LeftChoice[F, I, O, C, S](self)
//     transparent inline def right[C]: FreeScanK[F, Either[C, I], Either[C, O]] = RightChoice[F, I, O, C, S](self)
//     transparent inline def +++[I2, O2, S2 <: Matchable](
//       that: FreeScanK[F, I2, O2]
//     ): FreeScanK[F, Either[I, I2], Either[O, O2]] = PlusPlus(self, that)
//     transparent inline def |||[I2, S2 <: Matchable](
//       that: FreeScanK[F, I2, O]
//     ): FreeScanK[F, Either[I, I2], O] = FanIn(self, that)
//   end extension

//   inline def id[F[_, _], A]: FreeScanK[F, A, A] = Id[F, A]()
//   inline def op[F[_, _] <: { type State <: Tuple }, I, O, S <: Tuple](
//     fab: F[I, O] { type State = S }
//   ): Aux[F, I, O, S] =
//     Op(fab)

//   // Optimizer (no-op for now to keep types simple and avoid inline recursion)
//   private def optimize[F[_, _], I, O, S <: Matchable](
//     fs: FreeScanK.Aux[F, I, O, S]
//   ): FreeScanK.Aux[F, I, O, S] =
//     fs match
//       case AndThen(Id(), r) => optimize(r.asInstanceOf[FreeScanK.Aux[F, I, O, S]])
//       case AndThen(l, Id()) => optimize(l.asInstanceOf[FreeScanK.Aux[F, I, O, S]])
//       case Zip(l, r)        => optimize(Zip(l, r).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
//       case First(s)         => optimize(First(s).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
//       case Second(s)        => optimize(Second(s).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
//       case LeftChoice(s)    => optimize(LeftChoice(s).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
//       case RightChoice(s)   => optimize(RightChoice(s).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
//       case PlusPlus(l, r)   => optimize(PlusPlus(l, r).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
//       case FanIn(l, r)      => optimize(FanIn(l, r).asInstanceOf[FreeScanK.Aux[F, I, O, S]])
//       case other            => other
