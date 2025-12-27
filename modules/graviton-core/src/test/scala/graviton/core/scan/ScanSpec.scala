package graviton.core.scan

import kyo.Record
import kyo.Record.`~`
import zio.Chunk
import zio.test.*

object ScanSpec extends ZIOSpecDefault:

  type CountState = Record["count" ~ Long]
  type SumState   = Record["sum" ~ Long]
  type BothState  = Record[("count" ~ Long) & ("sum" ~ Long)]

  private val counting: Scan.Aux[Long, Long, CountState] =
    Scan.fold[Long, Long, CountState]((Record.empty & ("count" ~ 0L)).asInstanceOf[CountState]) { (s, _) =>
      val next = s.count + 1
      val ns   = (Record.empty & ("count" ~ next)).asInstanceOf[CountState]
      (ns, next)
    } { s =>
      (s, Chunk.empty)
    }

  private val summing: Scan.Aux[Long, Long, SumState] =
    Scan.fold[Long, Long, SumState]((Record.empty & ("sum" ~ 0L)).asInstanceOf[SumState]) { (s, in) =>
      val next = s.sum + in
      val ns   = (Record.empty & ("sum" ~ next)).asInstanceOf[SumState]
      (ns, next)
    } { s =>
      (s, Chunk.empty)
    }

  override def spec: Spec[TestEnvironment, Any] =
    suite("Scan (kyo.Record state, composable)")(
      test(">>> composes and keeps composed state as Record intersection") {
        val pipeline = counting >>> summing
        val _        = summon[pipeline.S =:= BothState]

        val (finalS, outputs) = pipeline.runChunk(List(10L, 20L, 30L))

        // outputs are running sums of emitted counts: count emits 1,2,3; sum emits 1,3,6
        assertTrue(outputs.toList == List(1L, 3L, 6L)) &&
        assertTrue(finalS.count == 3L) &&
        assertTrue(finalS.sum == 6L)
      },
      test("dimap preserves state type member") {
        val s = counting.dimap[String, String](_.toLong, _.toString)
        val _ = summon[s.S =:= CountState]

        val (finalS, outputs) = s.runChunk(List("a".replace("a", "1"), "2"))
        assertTrue(outputs.toList == List("1", "2")) &&
        assertTrue(finalS.count == 2L)
      },
    )
