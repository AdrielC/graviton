package graviton.core.scan

import zio.Chunk
import zio.test.*

object ScanSpec extends ZIOSpecDefault:

  final case class CountState(count: Long)
  final case class SumState(sum: Long)

  private sealed trait CapCount
  private sealed trait CapSum

  private val counting: Scan.Aux[Long, Long, CountState, CapCount] =
    Scan
      .fold[Long, Long, CountState](CountState(0L)) { (s, _) =>
        val next = s.count + 1L
        (CountState(next), next)
      }(s => (s, Chunk.empty))
      .withCaps[CapCount]

  private val summing: Scan.Aux[Long, Long, SumState, CapSum] =
    Scan
      .fold[Long, Long, SumState](SumState(0L)) { (s, in) =>
        val next = s.sum + in
        (SumState(next), next)
      }(s => (s, Chunk.empty))
      .withCaps[CapSum]

  override def spec: Spec[TestEnvironment, Any] =
    suite("Scan (composable, capability-tracked)")(
      test(">>> composes and tracks capabilities") {
        val pipeline =
          counting >>> summing

        val _: Scan.Aux[Long, Long, (CountState, SumState), Scan.CapUnion[CapCount, CapSum]] = pipeline

        val (finalS, outputs) = pipeline.runChunk(List(10L, 20L, 30L))
        val (countS, sumS)    = finalS

        // outputs are running sums of emitted counts: count emits 1,2,3; sum emits 1,3,6
        assertTrue(outputs.toList == List(1L, 3L, 6L)) &&
        assertTrue(countS.count == 3L) &&
        assertTrue(sumS.sum == 6L)
      },
      test("dimap preserves state type member") {
        val s: Scan.Aux[String, String, CountState, CapCount] =
          counting.dimap[String, String](_.toLong, _.toString)

        val (finalS, outputs) = s.runChunk(List("a".replace("a", "1"), "2"))
        assertTrue(outputs.toList == List("1", "2")) &&
        assertTrue(finalS.count == 2L)
      },
    )
