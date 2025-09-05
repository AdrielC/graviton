package graviton

import zio.*
import zio.stream.*
import zio.test.*

object ComplexFreeScanSpec extends ZIOSpecDefault:
  def spec = suite("ComplexFreeScanSpec")(
    test("moving average then delta of averages") {
      def movingAverageFree(n: Int): FreeScan.Aux[Double, Double, (List[Double], Double)] =
        FreeScan.statefulTuple[Double, Double, (List[Double], Double)]((List.empty[Double], 0.0)) { case ((buf, sum), d) =>
          val buf1 = (d :: buf).take(n)
          val sum1 = (sum + d) - (if buf.size >= n then buf.last else 0.0)
          val avg  = sum1 / buf1.size.toDouble
          ((buf1, sum1), Chunk.single(avg))
        }(_ => Chunk.empty)

      def deltaFree: FreeScan.Aux[Double, Double, Tuple1[Option[Double]]] =
        FreeScan.statefulTuple(Tuple1(Option.empty[Double])) { (st, a: Double) =>
          val prev = st._1
          val out  = prev.map(p => a - p).getOrElse(a)
          (Tuple1(Some(a)), Chunk.single(out))
        }(_ => Chunk.empty)

      val diffAves = movingAverageFree(3) >>> deltaFree
      val scan     = diffAves.compileOptimized
      val in       = ZStream(1.0, 2.0, 3.0, 6.0)
      for out <- in.via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1.0, 0.5, 0.5, 1.6666666666666665))
    },
    test("pair: moving averages and their difference computed in parallel") {
      def movingAverageFree(n: Int): FreeScan.Aux[Double, Double, (List[Double], Double)] =
        FreeScan.statefulTuple[Double, Double, (List[Double], Double)]((List.empty[Double], 0.0)) { case ((buf, sum), d) =>
          val buf1 = (d :: buf).take(n)
          val sum1 = (sum + d) - (if buf.size >= n then buf.last else 0.0)
          val avg  = sum1 / buf1.size.toDouble
          ((buf1, sum1), Chunk.single(avg))
        }(_ => Chunk.empty)

      val avg  = movingAverageFree(2)
      val avg3 = movingAverageFree(3)
      val both = (avg &&& avg3).map { case (a2, a3) => a2 - a3 }
      val scan = both.compileOptimized
      val in   = ZStream(1.0, 2.0, 3.0, 4.0)
      for out <- in.via(scan.toPipeline).runCollect
      yield assertTrue(out.length == 4)
    },
    test("rate of change of moving average over timestamped data") {
      def movingAverageFree(n: Int): FreeScan.Aux[Double, Double, (List[Double], Double)] =
        FreeScan.statefulTuple[Double, Double, (List[Double], Double)]((List.empty[Double], 0.0)) { case ((buf, sum), d) =>
          val buf1 = (d :: buf).take(n)
          val sum1 = (sum + d) - (if buf.size >= n then buf.last else 0.0)
          val avg  = sum1 / buf1.size.toDouble
          ((buf1, sum1), Chunk.single(avg))
        }(_ => Chunk.empty)

      def deltaTime: FreeScan.Aux[Long, Long, Tuple1[Option[Long]]] =
        FreeScan.statefulTuple(Tuple1(Option.empty[Long])) { (st, a: Long) =>
          val prev = st._1
          val out  = prev.map(p => a - p).getOrElse(a)
          (Tuple1(Some(a)), Chunk.single(out))
        }(_ => Chunk.empty)

      def deltaAvg: FreeScan.Aux[Double, Double, Tuple1[Option[Double]]] =
        FreeScan.statefulTuple(Tuple1(Option.empty[Double])) { (st, a: Double) =>
          val prev = st._1
          val out  = prev.map(p => a - p).getOrElse(a)
          (Tuple1(Some(a)), Chunk.single(out))
        }(_ => Chunk.empty)

      val avg2       = movingAverageFree(2)
      val pipeline   = (
        (avg2.second[Long]) >>> (deltaTime.first[Double]) >>> (deltaAvg.second[Long])
      ).map { case (dt, da) => if dt == 0 then 0.0 else da / dt.toDouble }
        .compileOptimized
      val in         = ZStream((0L, 10.0), (2L, 14.0), (4L, 18.0))
      for out <- in.via(pipeline.toPipeline).runCollect
      yield assertTrue(out == Chunk(0.0, 1.0, 2.0))
    },
    test("compute rate of change between successive moving averages") {
      def movingAverageFree(n: Int): FreeScan.Aux[Double, Double, (List[Double], Double)] =
        FreeScan.statefulTuple[Double, Double, (List[Double], Double)]((List.empty[Double], 0.0)) { case ((buf, sum), d) =>
          val buf1 = (d :: buf).take(n)
          val sum1 = (sum + d) - (if buf.size >= n then buf.last else 0.0)
          val avg  = sum1 / buf1.size.toDouble
          ((buf1, sum1), Chunk.single(avg))
        }(_ => Chunk.empty)

      def deltaFree: FreeScan.Aux[Double, Double, Tuple1[Option[Double]]] =
        FreeScan.statefulTuple(Tuple1(Option.empty[Double])) { (st, a: Double) =>
          val prev = st._1
          val out  = prev.map(p => a - p).getOrElse(a)
          (Tuple1(Some(a)), Chunk.single(out))
        }(_ => Chunk.empty)

      val avg   = movingAverageFree(3)
      val rates = avg >>> deltaFree
      val scan  = rates.compileOptimized
      val in    = ZStream.fromIterable((1 to 10).map(_.toDouble))
      for out <- in.via(scan.toPipeline).runCollect
      yield assertTrue(out.nonEmpty)
    }
  )

