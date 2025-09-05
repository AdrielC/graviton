package graviton.timeseries

import graviton.*
import zio.*
import zio.schema.Schema

// A tiny time-series DSL with pure-data ops and associated state types
sealed trait TSOp[I, O]:
  type State <: Tuple

object TSOp:
  final case class DeltaLong() extends TSOp[Long, Long]:
    type State = Tuple1[Option[Long]]

  final case class DeltaDouble() extends TSOp[Double, Double]:
    type State = Tuple1[Option[Double]]

  final case class MovingAvgDouble(n: Int) extends TSOp[Double, Double]:
    type State = (List[Double], Double)

  // New pure-data ops
  final case class PairSubDouble() extends TSOp[(Double, Double), Double]:
    type State = EmptyTuple

  final case class RateFromPair() extends TSOp[(Long, Double), Double]:
    type State = EmptyTuple

  object Schemas:
    val tuple1OptLong: Schema[Tuple1[Option[Long]]]     =
      Schema[Option[Long]].transform[Tuple1[Option[Long]]](Tuple1(_), _._1)
    val tuple1OptDouble: Schema[Tuple1[Option[Double]]] =
      Schema[Option[Double]].transform[Tuple1[Option[Double]]](Tuple1(_), _._1)
    val movingState: Schema[(List[Double], Double)]     = Schema[(List[Double], Double)]
    val emptyTuple: Schema[EmptyTuple]                  =
      Schema[Unit].transform[EmptyTuple](_ => EmptyTuple, _ => ())

  given FreeScanK.Interpreter[TSOp] with
    def toScan[I, O, S <: Tuple](op: TSOp[I, O] { type State = S }): Scan.Aux[I, O, S] =
      op match
        case DeltaLong()        =>
          Scan
            .statefulTuple(Tuple1(Option.empty[Long])) { (st, a: Long) =>
              val prev = st._1
              val out  = prev.map(p => a - p).getOrElse(a)
              (Tuple1(Some(a)), Chunk.single(out))
            }(_ => Chunk.empty)
            .asInstanceOf[Scan.Aux[I, O, S]]
        case DeltaDouble()      =>
          Scan
            .statefulTuple(Tuple1(Option.empty[Double])) { (st, a: Double) =>
              val prev = st._1
              val out  = prev.map(p => a - p).getOrElse(a)
              (Tuple1(Some(a)), Chunk.single(out))
            }(_ => Chunk.empty)
            .asInstanceOf[Scan.Aux[I, O, S]]
        case MovingAvgDouble(n) =>
          Scan
            .statefulTuple[Double, Double, (List[Double], Double)]((List.empty[Double], 0.0)) { case ((buf, sum), d) =>
              val buf1 = (d :: buf).take(n)
              val sum1 = (sum + d) - (if buf.size >= n then buf.last else 0.0)
              val avg  = sum1 / buf1.size.toDouble
              ((buf1, sum1), Chunk.single(avg))
            }(_ => Chunk.empty)
            .asInstanceOf[Scan.Aux[I, O, S]]
        case PairSubDouble()    =>
          Scan
            .stateless1[(Double, Double), Double] { case (a, b) => a - b }
            .asInstanceOf[Scan.Aux[I, O, S]]
        case RateFromPair()     =>
          Scan
            .stateless1[(Long, Double), Double] { case (dt, da) => if dt == 0 then 0.0 else da / dt.toDouble }
            .asInstanceOf[Scan.Aux[I, O, S]]

    def stateSchema[I, O, S <: Tuple](op: TSOp[I, O] { type State = S }): Schema[S] =
      (op: TSOp[I, O]) match
        case _: DeltaLong       => Schemas.tuple1OptLong.asInstanceOf[Schema[S]]
        case _: DeltaDouble     => Schemas.tuple1OptDouble.asInstanceOf[Schema[S]]
        case _: MovingAvgDouble => Schemas.movingState.asInstanceOf[Schema[S]]
        case _: PairSubDouble   => Schemas.emptyTuple.asInstanceOf[Schema[S]]
        case _: RateFromPair    => Schemas.emptyTuple.asInstanceOf[Schema[S]]

    def stateLabels[I, O, S <: Tuple](op: TSOp[I, O] { type State = S }): Chunk[String] =
      (op: TSOp[I, O]) match
        case _: DeltaLong       => Chunk("previous")
        case _: DeltaDouble     => Chunk("previous")
        case _: MovingAvgDouble => Chunk("buffer", "sum")
        case _: PairSubDouble   => Chunk.empty
        case _: RateFromPair    => Chunk.empty
