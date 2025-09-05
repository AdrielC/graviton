package graviton

import zio.*
import zio.stream.*
import zio.test.*

object ComplexFreeScanSpec extends ZIOSpecDefault:
  def spec = suite("ComplexFreeScanSpec")(
    test("moving average then delta of averages") {
      import graviton.timeseries.TSOp
      import graviton.FreeScanK
      val avg3     = FreeScanK.op[TSOp, Double, Double, TSOp.MovAvgState[Double] *: EmptyTuple](TSOp.MovingAvg.make[Double](3))
      val delta    = FreeScanK.op[TSOp, Double, Double, TSOp.Prev[Double] *: EmptyTuple](TSOp.Delta.make[Double])
      val diffAves = avg3 >>> delta
      // labels should append in order of composition
      val labs = diffAves.labelsFromType
      val scan     = diffAves.compileOptimized
      val in       = ZStream(1.0, 2.0, 3.0, 6.0)
      for out <- in.via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1.0, 0.5, 0.5, 1.6666666666666665)) &&
        assertTrue(labs == Chunk("buffer", "sum", "previous"))
    },
    test("pair: moving averages and their difference computed in parallel (labels preserved)") {
      import graviton.timeseries.TSOp
      import graviton.FreeScanK
      val avg2 = FreeScanK.op[TSOp, Double, Double, TSOp.MovAvgState[Double] *: EmptyTuple](TSOp.MovingAvg.make[Double](2))
      val avg3 = FreeScanK.op[TSOp, Double, Double, TSOp.MovAvgState[Double] *: EmptyTuple](TSOp.MovingAvg.make[Double](3))
      val both = (avg2 &&& avg3) >>> FreeScanK.op[TSOp, (Double, Double), Double, EmptyTuple](TSOp.PairSub.make[Double])
      val scan = both.compileOptimized.toPipeline
      val in   = ZStream(1.0, 2.0, 3.0, 4.0)
      val labelsLeft  = avg2.labelsFromType
      val labelsRight = avg3.labelsFromType
      val labelsBoth  = (avg2 &&& avg3).labelsFromType
      for out <- in.via(scan).runCollect
      yield assertTrue(out.length == 4) &&
        assertTrue(labelsLeft == Chunk("buffer", "sum")) &&
        assertTrue(labelsRight == Chunk("buffer", "sum")) &&
        assertTrue(labelsBoth == Chunk("buffer", "sum", "buffer", "sum"))
    },
    test("rate of change of moving average over timestamped data") {
      import graviton.timeseries.TSOp
      import graviton.FreeScanK
      val avg2     = FreeScanK.op[TSOp, Double, Double, TSOp.MovAvgState[Double] *: EmptyTuple](TSOp.MovingAvg.make[Double](2))
      val dT       = FreeScanK.op[TSOp, Long, Long, TSOp.Prev[Long] *: EmptyTuple](TSOp.Delta.make[Long])
      val dA       = FreeScanK.op[TSOp, Double, Double, TSOp.Prev[Double] *: EmptyTuple](TSOp.Delta.make[Double])
      val rate     = FreeScanK.op[TSOp, (Long, Double), Double, EmptyTuple](TSOp.RateFromPair.make[Double])
      val free     = (avg2.second[Long]) >>> (dT.first[Double]) >>> (dA.second[Long]) >>> rate
      val pipeline = free.compileOptimized.toPipeline
      val in       = ZStream((0L, 0.0), (2L, 14.0), (4L, 18.0))
      for out <- in.via(pipeline).runCollect
      yield assertTrue(out == Chunk(0.0, 3.5, 4.5))
    },
    test("compute rate of change between successive moving averages") {
      import graviton.timeseries.TSOp
      import graviton.FreeScanK
      val avg  = FreeScanK.op[TSOp, Double, Double, TSOp.MovAvgState[Double] *: EmptyTuple](TSOp.MovingAvg.make[Double](3))
      val dA   = FreeScanK.op[TSOp, Double, Double, TSOp.Prev[Double] *: EmptyTuple](TSOp.Delta.make[Double])
      val scan = (avg >>> dA).compileOptimized
      val in   = ZStream.fromIterable((1 to 10).map(_.toDouble))
      for out <- in.via(scan.toPipeline).runCollect
      yield assertTrue(out.nonEmpty)
    },
  )
