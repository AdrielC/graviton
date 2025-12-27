package graviton.core.scan

import graviton.core.bytes.HashAlgo
import graviton.core.scan.IngestTelemetryScan.IngestTelemetryEvent
import kyo.Tag.given
import zio.Chunk
import zio.test.*

object IngestTelemetryScanSpec extends ZIOSpecDefault:

  override def spec: Spec[Any, Any] =
    suite("IngestTelemetryScan")(
      test("emits step events and a final digest on flush") {
        val scan = IngestTelemetryScan(maxBytes = 1024L, sniffBytes = 16, largeCutoff = 1, algo = HashAlgo.runtimeDefault)

        val in = Chunk(
          Chunk.fromArray("hello ".getBytes("UTF-8")),
          Chunk.fromArray("world".getBytes("UTF-8")),
        )

        val (_, out) = scan.runChunk(in)

        val steps  = out.collect { case s: IngestTelemetryEvent.Step => s }
        val finals = out.collect { case f: IngestTelemetryEvent.Final => f }

        assertTrue(
          steps.length == 2,
          finals.length == 1,
          steps.last.bytes > 0L,
          finals.head.bytes == steps.last.bytes,
        )
      }
    )
