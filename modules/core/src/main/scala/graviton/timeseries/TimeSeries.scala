package graviton.timeseries

import graviton.Scan
import zio.Chunk

/**
 * Simple time-series oriented scans ported from fs2-timeseries.
 */
object TimeSeries:

  /**
   * Emits the difference between consecutive numeric inputs. First element is
   * emitted unchanged.
   */
  def delta[A](using num: Numeric[A]): Scan.Aux[A, A, Tuple1[Option[A]]] =
    Scan.statefulTuple(Tuple1(Option.empty[A])) { (st, a: A) =>
      val prev = st._1
      val out  = prev.map(p => num.minus(a, p)).getOrElse(a)
      (Tuple1(Some(a)), Chunk.single(out))
    }(_ => Chunk.empty)

  /** Moving average over a sliding window of size `n`. */
  def movingAverage[A](n: Int)(using num: Fractional[A]): Scan.Aux[A, A, Tuple2[List[A], A]] =
    import num.*
    require(n > 0, "window size must be positive")
    Scan.statefulTuple[A, A, Tuple2[List[A], A]]((List.empty[A], num.zero)) { case ((buf, sum), d) =>
      val buf1 = (d :: buf).take(n)
      val sum1 = (sum + d) - (if buf.size >= n then buf.last else num.zero)
      val avg  = sum1 / num.fromInt(buf1.size)
      ((buf1, sum1), Chunk.single(avg))
    }(_ => Chunk.empty)

  /** Count elements per fixed-size window using a tumbling strategy. */
  def countWindow(n: Int): Scan.Aux[Any, Long, Int] =
    require(n > 0, "window size must be positive")
    Scan.statefulTuple(0) { (st, _: Any) =>
      val c = st + 1
      if c >= n then (0, Chunk.single(c.toLong))
      else (c, Chunk.empty)
    }(st => if st > 0 then Chunk.single(st.toLong) else Chunk.empty)
