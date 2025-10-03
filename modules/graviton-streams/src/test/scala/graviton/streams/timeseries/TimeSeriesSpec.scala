package graviton.streams.timeseries

import zio.*
import zio.stream.*
import zio.test.*
import graviton.streams.timeseries.TimeSeries

object TimeSeriesSpec extends ZIOSpecDefault:
  def spec = suite("TimeSeriesSpec")(
    test("delta produces differences between consecutive values") {
      val scan = TimeSeries.delta[Int]
      for out <- ZStream(1, 4, 6).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(1, 3, 2))
    },
    test("movingAverage computes sliding averages") {
      val scan = TimeSeries.movingAverage[Double](2)
      for out <- ZStream(1.0, 2.0, 3.0).via(scan.toPipeline).runCollect
      yield assertTrue(
        out.map(d => math.round(d * 10) / 10.0) == Chunk(1.0, 1.5, 2.5)
      )
    },
    test("countWindow emits counts per window") {
      val scan = TimeSeries.countWindow(3)
      for out <- ZStream.range(0, 7).via(scan.toPipeline).runCollect
      yield assertTrue(out == Chunk(3, 3, 1))
    },
  )
