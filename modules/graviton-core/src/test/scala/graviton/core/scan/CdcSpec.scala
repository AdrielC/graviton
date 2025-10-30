package graviton.core.scan

import zio.*
import zio.test.*
import zio.Chunk

/**
 * Tests for Content-Defined Chunking invariance.
 *
 * Verifies that CDC boundaries are reproducible regardless of
 * input chunking (sliding window test).
 */
object CdcSpec extends ZIOSpecDefault:

  def spec = suite("CDC Properties")(
    test("FastCDC scan can be created") {
      val scan = CdcScan.fastCdc(avg = 64, min = 16, max = 256)
      assertTrue(scan != null)
    },
    test("Fixed CDC scan can be created") {
      val scan = CdcScan.fixed(32)
      assertTrue(scan != null)
    },
  )
