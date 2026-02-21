package graviton.core.scan

import zio.*
import zio.test.*

object ThroughputMonitorSpec extends ZIOSpecDefault:

  override def spec =
    suite("ThroughputMonitor")(
      test("tracks bytes and chunks") {
        val monitor     = ThroughputMonitor()
        val input       = List(
          Chunk.fromArray(Array.fill(100)(1.toByte)),
          Chunk.fromArray(Array.fill(200)(2.toByte)),
          Chunk.fromArray(Array.fill(300)(3.toByte)),
        )
        val (_, output) = monitor.runChunk(input)
        assertTrue(
          output.length == 3,
          output.flatMap(_.toList).length == 600,
        )
      },
      test("compiles to ZPipeline") {
        val pipeline = ThroughputMonitor().toPipeline
        for result <- zio.stream
                        .ZStream(
                          Chunk.fromArray(Array.fill(50)(1.toByte)),
                          Chunk.fromArray(Array.fill(50)(2.toByte)),
                        )
                        .via(pipeline)
                        .runCollect
        yield assertTrue(result.length == 2)
      },
      test("passes through data unchanged") {
        val monitor     = ThroughputMonitor()
        val data        = Chunk.fromArray(Array.tabulate(100)(_.toByte))
        val input       = List(data)
        val (_, output) = monitor.runChunk(input)
        assertTrue(
          output.length == 1,
          output(0) == data,
        )
      },
    )
