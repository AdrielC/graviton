package graviton.timeseries

import graviton.Scan
import zio.*
import zio.stream.*
import zio.Chunk

/** Simple time-series oriented scans ported from fs2-timeseries.
  */
object TimeSeries:

  /** Emits the difference between consecutive numeric inputs. First element is
    * emitted unchanged.
    */
  def delta[A](using num: Numeric[A]): Scan.Aux[A, A, Tuple1[Option[A]]] =
    Scan.statefulTuple(Tuple1(Option.empty[A])) { (st, a: A) =>
      val prev = st._1
      val out = prev.map(p => num.minus(a, p)).getOrElse(a)
      (Tuple1(Some(a)), Chunk.single(out))
    }(_ => Chunk.empty)

  /** Moving average over a sliding window of size `n`. */
  def movingAverage(n: Int)(using
      num: Fractional[Double]
  ): Scan.Aux[Double, Double, Tuple2[List[Double], Double]] =
    require(n > 0, "window size must be positive")
    Scan.statefulTuple((List.empty[Double], 0.0)) {
      case ((buf, sum), d: Double) =>
        val buf1 = (d :: buf).take(n)
        val sum1 = sum + d - (if buf.size >= n then buf.last else 0.0)
        val avg = sum1 / buf1.size
        ((buf1, sum1), Chunk.single(avg))
    }(_ => Chunk.empty)

  /** Count elements per fixed-size window using a tumbling strategy. */
  def countWindow(n: Int): Scan.Aux[Any, Long, Tuple1[Int]] =
    require(n > 0, "window size must be positive")
    Scan.statefulTuple(Tuple1(0)) { (st, _: Any) =>
      val c = st._1 + 1
      if c >= n then (Tuple1(0), Chunk.single(c.toLong))
      else (Tuple1(c), Chunk.empty)
    }(st => if st._1 > 0 then Chunk.single(st._1.toLong) else Chunk.empty)
