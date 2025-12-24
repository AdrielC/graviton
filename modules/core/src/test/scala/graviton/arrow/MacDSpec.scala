package graviton.arrow

import zio.Chunk
import zio.test.*

import java.nio.charset.StandardCharsets

object MacDSpec extends ZIOSpecDefault:

  private def chunk(str: String): Chunk[Byte] =
    Chunk.fromArray(str.getBytes(StandardCharsets.US_ASCII))

  def spec = suite("MacD")(
    test("macdFlow halts on sentinel and exposes leftovers") {
      val flow                        = MacD.macdFlow(frameBytes = 4, window = 3, stopMarker = chunk("STOP"))
      val first                       = chunk("HELLOSPACE")
      val second                      = chunk("STOP123")
      val (reports, accumulatorState) = MacD.drive(flow, List(first, second))
      assertTrue(reports.length == 2) &&
      assertTrue(reports(0).stopped == false) &&
      assertTrue(reports(0).hashes.nonEmpty) &&
      assertTrue(reports(1).stopped) &&
      assertTrue(reports(1).reason.exists(_.contains("stop"))) &&
      assertTrue(new String(reports(1).leftover.toArray, StandardCharsets.US_ASCII) == "3") &&
      assertTrue(new String(accumulatorState.buffer.toArray, StandardCharsets.US_ASCII) == "3")
    }
  )
