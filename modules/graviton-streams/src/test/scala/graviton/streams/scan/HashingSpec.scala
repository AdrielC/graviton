package graviton.streams.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen
import java.security.MessageDigest

/**
 * Property-based tests for hashing scans.
 *
 * Verifies:
 * - Determinism: same input produces same digest
 * - Split invariance: digest of concatenated parts equals digest of whole
 * - Edge cases: empty input, single byte, etc.
 */
object HashingSpec extends ZIOSpecDefault {

  // Reduce test samples to prevent OOM
  override def aspects = Chunk(TestAspect.samples(20))

  /** A simple hashing scan using Java's MessageDigest for testing */
  def sha256Scan: Scan[Byte, MessageDigest, Array[Byte]] =
    Scan.stateful[Byte, MessageDigest, Array[Byte]](
      initialState = MessageDigest.getInstance("SHA-256"),
      initialOutputs = Chunk.empty,
      onEnd = digest => Chunk.single(digest.digest()),
    ) { (digest, byte) =>
      digest.update(byte)
      (digest, Chunk.empty)
    }

  /** A scan that emits hash every N bytes */
  def sha256Every(n: Int): Scan[Byte, (MessageDigest, Long), Array[Byte]] =
    Scan.stateful[Byte, (MessageDigest, Long), Array[Byte]](
      initialState = (MessageDigest.getInstance("SHA-256"), 0L),
      initialOutputs = Chunk.empty,
      onEnd = { case (digest, count) =>
        if (count > 0) Chunk.single(digest.digest())
        else Chunk.empty
      },
    ) { case ((digest, count), byte) =>
      digest.update(byte)
      val newCount = count + 1
      if (newCount >= n) {
        val hash = digest.digest()
        // Reset the digest instead of creating a new instance to save memory
        ((digest, 0L), Chunk.single(hash))
      } else {
        ((digest, newCount), Chunk.empty)
      }
    }

  def spec = suite("Hashing Properties")(
    test("sha256 is deterministic") {
      check(TestGen.boundedBytes) { input =>
        val scan = sha256Scan
        for {
          result1 <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
          result2 <- ZStream.fromChunk(input).via(sha256Scan.pipeline).runCollect
        } yield assertTrue(
          result1.length == 1,
          result2.length == 1,
          result1.head.sameElements(result2.head),
        )
      }
    },
    test("sha256 handles empty input") {
      val scan = sha256Scan
      for {
        result <- ZStream.empty.via(scan.pipeline).runCollect
      } yield {
        // SHA-256 of empty string is a known constant
        val emptyHash = MessageDigest.getInstance("SHA-256").digest()
        assertTrue(
          result.length == 1,
          result.head.sameElements(emptyHash),
        )
      }
    },
    test("sha256 handles single byte") {
      val scan  = sha256Scan
      val input = Chunk.single(42.toByte)
      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield {
        val expected = MessageDigest.getInstance("SHA-256").digest(Array(42.toByte))
        assertTrue(
          result.length == 1,
          result.head.sameElements(expected),
        )
      }
    },
    test("sha256 split invariance: hash(a ++ b) == hash(hash(a) + b) is false but chunk-independent") {
      // This test verifies that regardless of how we chunk the input,
      // we get the same final hash
      check(TestGen.boundedBytes) { input =>
        val scan = sha256Scan

        // Hash the whole thing at once
        val whole = ZStream.fromChunk(input).via(scan.pipeline).runCollect

        // Hash with different chunking
        val rechunked = if (input.length > 2) {
          val mid = input.length / 2
          ZStream
            .fromChunks(input.take(mid), input.drop(mid))
            .via(scan.pipeline)
            .runCollect
        } else {
          whole
        }

        for {
          w <- whole
          r <- rechunked
        } yield assertTrue(
          w.length == 1,
          r.length == 1,
          w.head.sameElements(r.head),
        )
      }
    },
    test("sha256Every emits digest at boundaries") {
      val n     = 16
      val scan  = sha256Every(n)
      val input = Chunk.fromIterable(1 to 50).map(_.toByte)

      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield {
        // Should emit 3 hashes: bytes 0-15, 16-31, 32-49 (flush)
        val expectedCount = (input.length + n - 1) / n
        assertTrue(result.length == expectedCount)
      }
    },
    test("sha256Every with boundary-aligned input") {
      val n     = 16
      val scan  = sha256Every(n)
      val input = Chunk.fromIterable(1 to 32).map(_.toByte) // Exactly 2 boundaries

      for {
        result <- ZStream.fromChunk(input).via(scan.pipeline).runCollect
      } yield assertTrue(result.length == 2)
    },
    test("sha256Every respects chunk boundaries in stream") {
      val n     = 8
      val scan  = sha256Every(n)
      val input = Chunk.fromIterable(1 to 100).map(_.toByte)

      // Hash with different chunk sizes
      val chunked1  = ZStream.fromChunk(input).rechunk(1).via(scan.pipeline).runCollect
      val chunked7  = ZStream.fromChunk(input).rechunk(7).via(scan.pipeline).runCollect
      val chunked13 = ZStream.fromChunk(input).rechunk(13).via(scan.pipeline).runCollect

      for {
        r1  <- chunked1
        r7  <- chunked7
        r13 <- chunked13
      } yield assertTrue(
        r1.length == r7.length,
        r7.length == r13.length,
        r1.zip(r7).forall { case (a, b) => a.sameElements(b) },
        r7.zip(r13).forall { case (a, b) => a.sameElements(b) },
      )
    },
    test("hashing scan composes with other scans") {
      val hashScan  = sha256Scan
      val countScan = Scan.foldLeft[Array[Byte], Long](0L)((acc, _) => acc + 1)
      val composed  = hashScan.andThen(countScan)

      check(TestGen.boundedBytes) { input =>
        for {
          result <- ZStream.fromChunk(input).via(composed.pipeline).runCollect
        } yield {
          // Should produce counts: 0, 1, 1 (initial count, hash emitted, final count)
          assertTrue(result.nonEmpty)
        }
      }
    },
  )
}
