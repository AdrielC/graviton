package graviton

import zio.*
import zio.test.*
import graviton.timeseries.TSOp

object FreeScanKLabelsSpec extends ZIOSpecDefault:
  def spec = suite("FreeScanKLabelsSpec")(
    test(">>> concatenates labels left then right") {
      val avg  = FreeScanK.op[TSOp, Double, Double, TSOp.MovAvgState[Double] *: EmptyTuple](TSOp.MovingAvg.make[Double](3))
      val delt = FreeScanK.op[TSOp, Double, Double, TSOp.Prev[Double] *: EmptyTuple](TSOp.Delta.make[Double])
      val comp = avg >>> delt
      val labs = comp.labelsFromType
      assertTrue(labs == Chunk("buffer", "sum", "previous"))
    },
    test("&&& duplicates labels across both branches in order") {
      val avg2 = FreeScanK.op[TSOp, Double, Double, TSOp.MovAvgState[Double] *: EmptyTuple](TSOp.MovingAvg.make[Double](2))
      val avg3 = FreeScanK.op[TSOp, Double, Double, TSOp.MovAvgState[Double] *: EmptyTuple](TSOp.MovingAvg.make[Double](3))
      val both = avg2 &&& avg3
      val labs = both.labelsFromType
      assertTrue(labs == Chunk("buffer", "sum", "buffer", "sum"))
    },
    test("*** product labels concat left then right") {
      val avg2 = FreeScanK.op[TSOp, Double, Double, TSOp.MovAvgState[Double] *: EmptyTuple](TSOp.MovingAvg.make[Double](2))
      val delt = FreeScanK.op[TSOp, Double, Double, TSOp.Prev[Double] *: EmptyTuple](TSOp.Delta.make[Double])
      val prod = avg2 *** delt
      val labs = prod.labelsFromType
      assertTrue(labs == Chunk("buffer", "sum", "previous"))
    },
    test("first keeps labels of src; second keeps labels of src") {
      val avg = FreeScanK.op[TSOp, Double, Double, TSOp.MovAvgState[Double] *: EmptyTuple](TSOp.MovingAvg.make[Double](3))
      assertTrue(avg.first[String].labelsFromType == Chunk("buffer", "sum")) &&
      assertTrue(avg.second[String].labelsFromType == Chunk("buffer", "sum"))
    },
    test("+++ and ||| preserve combined labels") {
      val a = FreeScanK.op[TSOp, Int, Int, TSOp.Prev[Int] *: EmptyTuple](TSOp.Delta.make[Int])
      val b = FreeScanK.op[TSOp, Int, Int, TSOp.Prev[Int] *: EmptyTuple](TSOp.Delta.make[Int])
      val p = a +++ b
      val f = a ||| b
      assertTrue(p.labelsFromType == Chunk("previous", "previous")) &&
      assertTrue(f.labelsFromType == Chunk("previous", "previous"))
    },
  )

