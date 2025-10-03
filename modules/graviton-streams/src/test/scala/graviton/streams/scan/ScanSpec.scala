package graviton.streams.scan

import zio.Chunk
import zio.stream.ZStream
import zio.test.*

object ScanSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment, Any] =
    suite("ScanSpec")(
      test("foldLeft mirrors fs2 scan semantics") {
        val scan   = Scan.foldLeft[Int, Int](0)(_ + _)
        val stream = ZStream.fromChunks(Chunk(1, 2), Chunk(3))
        scan.applyTo(stream).runCollect.map { result =>
          assertTrue(result == Chunk(0, 1, 3, 6))
        }
      },
      test("onEnd flushes trailing state") {
        val scan    =
          Scan.stateful[Int, Int, Int](0, Chunk.empty, state => Chunk.single(state)) { (state, next) =>
            val updated = state + next
            (updated, Chunk.single(updated))
          }
        val outputs = scan.applyTo(ZStream(1, 2)).runCollect
        outputs.map { chunk =>
          assertTrue(chunk == Chunk(1, 3, 3))
        }
      },
      test("andThen composes scans") {
        val scanA  = Scan.foldLeft[Int, Int](0)(_ + _)
        val scanB  = Scan.foldLeft[Int, Int](0)(_ + _)
        val stream = ZStream(1, 2, 3)
        (scanA.andThen(scanB)).applyTo(stream).runCollect.map { chunk =>
          assertTrue(chunk == Chunk(0, 0, 1, 4, 10))
        }
      },
    )
