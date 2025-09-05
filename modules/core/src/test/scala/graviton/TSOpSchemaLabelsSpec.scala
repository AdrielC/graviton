package graviton

import zio.*
import zio.test.*
import zio.schema.Schema
import graviton.timeseries.TSOp

object TSOpSchemaLabelsSpec extends ZIOSpecDefault:
  def spec = suite("TSOpSchemaLabelsSpec")(
    test("Delta schema/labels") {
      val d   = TSOp.Delta.make[Int]
      val sch = summon[FreeScanK.Interpreter[TSOp]].stateSchema(d)
      val lab = summon[FreeScanK.Interpreter[TSOp]].stateLabels(d)
      assertTrue(lab == Chunk("previous")) && assertTrue(sch.asInstanceOf[Schema[?]] != null)
    },
    test("MovingAvg schema/labels") {
      val m   = TSOp.MovingAvg.make[Double](3)
      val sch = summon[FreeScanK.Interpreter[TSOp]].stateSchema(m)
      val lab = summon[FreeScanK.Interpreter[TSOp]].stateLabels(m)
      assertTrue(lab == Chunk("buffer", "sum")) && assertTrue(sch.asInstanceOf[Schema[?]] != null)
    },
  )
