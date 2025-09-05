package graviton

import zio.*
import zio.stream.*
import zio.test.*

object ComplexFreeScanSpec extends ZIOSpecDefault:
  def spec = suite("ComplexFreeScanSpec")(
    test("moving average then delta of averages") {
      import graviton.timeseries.TSOp
      import graviton.FreeScanK
      val avg3      = FreeScanK.op[TSOp, Double, Double, (List[Double], Double)](TSOp.MovingAvgDouble(3))
      val delta     = FreeScanK.op[TSOp, Double, Double, Tuple1[Option[Double]]](TSOp.DeltaDouble())
      val diffAves  = avg3 >>> delta
      val scan      = diffAves.compileOptimized
      val in       = ZStream(1.0, 2.0, 3.0, 6.0)
      for out <- in.via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1.0, 0.5, 0.5, 1.6666666666666665))
    },
    test("pair: moving averages and their difference computed in parallel") {
      import graviton.timeseries.TSOp
      import graviton.FreeScanK
      val avg2 = FreeScanK.op[TSOp, Double, Double, (List[Double], Double)](TSOp.MovingAvgDouble(2))
      val avg3 = FreeScanK.op[TSOp, Double, Double, (List[Double], Double)](TSOp.MovingAvgDouble(3))
      val both = (avg2 &&& avg3) >>> FreeScanK.op[TSOp, (Double, Double), Double, EmptyTuple](TSOp.PairSubDouble())
      val scan = both.compileOptimized.toPipeline
      val in   = ZStream(1.0, 2.0, 3.0, 4.0)
      for out <- in.via(scan).runCollect
      yield assertTrue(out.length == 4)
    },
    test("rate of change of moving average over timestamped data") {
      import graviton.timeseries.TSOp
      import graviton.FreeScanK
      val avg2       = FreeScanK.op[TSOp, Double, Double, (List[Double], Double)](TSOp.MovingAvgDouble(2))
      val dT         = FreeScanK.op[TSOp, Long, Long, Tuple1[Option[Long]]](TSOp.DeltaLong())
      val dA         = FreeScanK.op[TSOp, Double, Double, Tuple1[Option[Double]]](TSOp.DeltaDouble())
      val rate       = FreeScanK.op[TSOp, (Long, Double), Double, EmptyTuple](TSOp.RateFromPair())
      val free       = (avg2.second[Long]) >>> (dT.first[Double]) >>> (dA.second[Long]) >>> rate
      val pipeline   = free.compileOptimized.toPipeline
      val in         = ZStream((0L, 10.0), (2L, 14.0), (4L, 18.0))
      for out <- in.via(pipeline).runCollect
      yield assertTrue(out == Chunk(0.0, 1.0, 2.0))
    },
    test("compute rate of change between successive moving averages") {
      import graviton.timeseries.TSOp
      import graviton.FreeScanK
      val avg   = FreeScanK.op[TSOp, Double, Double, (List[Double], Double)](TSOp.MovingAvgDouble(3))
      val dA    = FreeScanK.op[TSOp, Double, Double, Tuple1[Option[Double]]](TSOp.DeltaDouble())
      val scan  = (avg >>> dA).compileOptimized
      val in    = ZStream.fromIterable((1 to 10).map(_.toDouble))
      for out <- in.via(scan.toPipeline).runCollect
      yield assertTrue(out.nonEmpty)
    }
  )

