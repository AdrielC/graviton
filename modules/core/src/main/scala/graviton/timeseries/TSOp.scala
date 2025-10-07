package graviton.timeseries

import graviton.*
import zio.*
import zio.schema.{DeriveSchema, Schema}

// A tiny time-series DSL with pure-data ops and named state case classes
sealed trait TSOp[-I, +O]:
  type State <: Tuple

object TSOp:
  infix type :=>:[-I, +O] = TSOp[I, O]

  sealed trait State                                       extends Product
  // Named states (kept for schema derivation examples)
  final case class Prev[A](previous: Option[A])            extends State
  final case class MovAvgState[A](buffer: List[A], sum: A) extends State

  // Ops (generic over element A)
  final case class Delta[A](numeric: Numeric[A], schemaA: Schema[A]) extends TSOp[A, A]:
    type State = Tuple1[Prev[A]]
  object Delta:
    def make[A](using num: Numeric[A], sa: Schema[A]): Delta[A] = Delta(num, sa)

  final case class MovingAvg[A](n: Int, frac: Fractional[A], schemaA: Schema[A]) extends TSOp[A, A]:
    override type State = Tuple1[MovAvgState[A]]
  object MovingAvg:
    inline def make[A](inline n: Int)(using f: Fractional[A], sa: Schema[A]): MovingAvg[A] = MovingAvg(n, f, sa)

  // Pure-data stateless ops
  final case class PairSub[A](numeric: Numeric[A], schemaA: Schema[A]) extends TSOp[(A, A), A]:
    type State = EmptyTuple
  object PairSub:
    inline def make[A](using n: Numeric[A], sa: Schema[A]): PairSub[A] = PairSub(n, sa)

  final case class RateFromPair[A](frac: Fractional[A], schemaA: Schema[A]) extends TSOp[(Long, A), A]:
    type State = EmptyTuple
  object RateFromPair:
    inline def make[A](using f: Fractional[A], sa: Schema[A]): RateFromPair[A] = RateFromPair(f, sa)

  object Schemas:
    def singleElemTuple[T](schemaT: Schema[T]): Schema[Tuple1[T]] =
      schemaT.transform[Tuple1[T]](t => t *: EmptyTuple, { case h *: _ => h })
    def emptyTuple: Schema[EmptyTuple]                            = Schema[Unit].transform(_ => EmptyTuple, _ => ())

    given prevSchema:  [A] => (sa: Schema[A]) => Schema[Prev[A]]          = DeriveSchema.gen[Prev[A]]
    given movAvgSchema: [A] => (sa: Schema[A]) => Schema[MovAvgState[A]] = DeriveSchema.gen[MovAvgState[A]]
    def prevSchemaFor[A](sa: Schema[A]): Schema[Prev[A]]               =
      given Schema[A] = sa
      DeriveSchema.gen[Prev[A]]
    def movAvgSchemaFor[A](sa: Schema[A]): Schema[MovAvgState[A]]      =
      given Schema[A] = sa
      DeriveSchema.gen[MovAvgState[A]]

  inline given FreeScanK.Interpreter[:=>:]:

    type Flatten[A] <: Tuple | A = A match  
      case a *: b => a *: Flatten[b]
      case _      => Tuple1[A]

    override type State = TSOp.State
    def toScan[I, O, S <: Tuple](op: I :=>: O): Scan.Aux[I, O, S] =
      op match
        case d: Delta[a]        =>
          given num: Numeric[a] = d.numeric
          Scan
            .stateful[a, a, Flatten[Prev[a]]](Tuple(Prev[a](None))) { (st, a0) =>
              val out = st._1.previous.map(p => num.minus(a0, p)).getOrElse(a0)
              (Tuple1(Prev(Some(a0))), Chunk.single(out))
            }(_ => Chunk.empty)
            .asInstanceOf[Scan.Aux[I, O, S]]
        case m: MovingAvg[a]    =>
          given frac: Fractional[a] = m.frac
          import frac.*
          val n                     = m.n
          Scan
            .stateful[a, a, Flatten[MovAvgState[a]]](Tuple1(MovAvgState[a](Nil, frac.zero))) { (st, d) =>
              val buf1 = (d :: st._1.buffer).take(n)
              val sum1 = (st._1.sum + d) - (if st._1.buffer.size >= n then st._1.buffer.last else frac.zero)
              val avg  = sum1 / fromInt(buf1.size)
              (Tuple1(MovAvgState(buf1, sum1)), Chunk.single(avg))
            }(_ => Chunk.empty)
            .asInstanceOf[Scan.Aux[I, O, S]]
        case p: PairSub[a]      =>
          given num: Numeric[a] = p.numeric
          Scan
            .stateless[(a, a), a] { case (x, y) => Chunk.single(num.minus(x, y)) }
            .asInstanceOf[Scan.Aux[I, O, S]]
        case r: RateFromPair[a] =>
          given frac: Fractional[a] = r.frac
          import frac.*
          Scan
            .stateless[(Long, a), a] { case (dt, da) =>
              val denom = fromInt(dt.toInt)
              Chunk.single(if dt == 0 then da else da / denom)
            }
            .asInstanceOf[Scan.Aux[I, O, S]]

    def stateSchema[I, O, S <: Tuple](op: I :=>: O): Schema[S] =
      (op: TSOp[I, O]) match
        case d: Delta[a]        => Schemas.singleElemTuple(Schemas.prevSchemaFor[a](d.schemaA)).asInstanceOf[Schema[S]]
        case m: MovingAvg[a]    => Schemas.singleElemTuple(Schemas.movAvgSchemaFor[a](m.schemaA)).asInstanceOf[Schema[S]]
        case _: PairSub[?]      => Schemas.emptyTuple.asInstanceOf[Schema[S]]
        case _: RateFromPair[?] => Schemas.emptyTuple.asInstanceOf[Schema[S]]

    def stateLabels[I, O, S <: Tuple](op: I :=>: O): Chunk[String] =
      (op: TSOp[I, O]) match
        case _: Delta[?]        => Chunk("previous")
        case _: MovingAvg[?]    => Chunk("buffer", "sum")
        case _: PairSub[?]      => Chunk.empty
        case _: RateFromPair[?] => Chunk.empty
