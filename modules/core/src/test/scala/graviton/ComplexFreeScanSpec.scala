package graviton

import zio.*
import zio.stream.*
import zio.test.*
import graviton.timeseries.TimeSeries

object ComplexFreeScanSpec extends ZIOSpecDefault:
  def spec = suite("ComplexFreeScanSpec")(
    test("moving average then delta of averages") {
      val avgScan  = TimeSeries.movingAverage[Double](3)
      val avgFree  = FreeScan.fromScan(avgScan)
      val diffAves = avgFree >>> FreeScan.fromScan(TimeSeries.delta[Double])
      val scan     = diffAves.compileOptimized
      val in       = ZStream(1.0, 2.0, 3.0, 6.0)
      for out <- in.via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1.0, 0.5, 0.5, 1.6666666666666665))
    },
    test("pair: moving averages and their difference computed in parallel") {
      val avg  = FreeScan.fromScan(TimeSeries.movingAverage[Double](2))
      val avg3 = FreeScan.fromScan(TimeSeries.movingAverage[Double](3))
      val both = (avg &&& avg3).map { case (a2, a3) => a2 - a3 }
      val scan = both.compileOptimized
      val in   = ZStream(1.0, 2.0, 3.0, 4.0)
      for out <- in.via(scan.toPipeline).runCollect
      yield assertTrue(out.length == 4)
    },
    test("rate of change of moving average over timestamped data") {
      val avg2       = FreeScan.fromScan(TimeSeries.movingAverage[Double](2))
      val deltaTime  = FreeScan.fromScan(TimeSeries.delta[Long])
      val deltaAvg   = FreeScan.fromScan(TimeSeries.delta[Double])
      val pipeline   = (
        (avg2.second[Long]) >>> (deltaTime.first[Double]) >>> (deltaAvg.second[Long])
      ).map { case (dt, da) => if dt == 0 then 0.0 else da / dt.toDouble }
        .compileOptimized
      val in         = ZStream((0L, 10.0), (2L, 14.0), (4L, 18.0))
      for out <- in.via(pipeline.toPipeline).runCollect
      yield assertTrue(out == Chunk(0.0, 1.0, 2.0))
    },
    test("compute rate of change between successive moving averages") {
      val avg   = FreeScan.fromScan(TimeSeries.movingAverage[Double](3))
      val rates = avg >>> FreeScan.fromScan(TimeSeries.delta[Double])
      val scan  = rates.compileOptimized
      val in    = ZStream.fromIterable((1 to 10).map(_.toDouble))
      for out <- in.via(scan.toPipeline).runCollect
      yield assertTrue(out.nonEmpty)
    }
  )

