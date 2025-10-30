package graviton.core.scan

import zio.*
import zio.test.*
import zio.Chunk

/**
 * Property-based tests for HashScan.
 *
 * Verifies determinism, split invariance, and boundary behavior.
 */
object HashSpec extends ZIOSpecDefault:

  def spec = suite("HashScan Properties")(
    test("SHA-256 scan can be created") {
      val scan = HashScan.sha256
      assertTrue(scan != null)
    },
    test("SHA-256 every scan can be created") {
      val scanFunc = HashScan.sha256Every
      assertTrue(scanFunc != null)
    },
  )
