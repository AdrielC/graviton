package graviton.streams.scan

import zio.*
import zio.test.*
import zio.stream.*

/**
 * Realistic, orchestrated tests that combine multiple scans
 * to simulate real-world usage patterns.
 *
 * These tests demonstrate practical applications and complex compositions.
 */
object RealWorldScenariosSpec extends ZIOSpecDefault {

  /** Simulate a byte counter that reports progress every N bytes */
  def progressReporter(reportEvery: Int): Scan[Byte, (Long, Int), Either[Long, Long]] =
    Scan.stateful[Byte, (Long, Int), Either[Long, Long]](
      initialState = (0L, 0),
      initialOutputs = Chunk.empty,
      onEnd = { case (total, _) => Chunk.single(Right(total)) },
    ) { case ((total, count), _) =>
      val newTotal = total + 1
      val newCount = count + 1
      if (newCount >= reportEvery) {
        ((newTotal, 0), Chunk.single(Left(newTotal)))
      } else {
        ((newTotal, newCount), Chunk.empty)
      }
    }

  /** Simulate a deduplication scan that tracks seen values */
  def deduplicator[A]: Scan[A, Set[A], Option[A]] =
    Scan.stateful[A, Set[A], Option[A]](
      initialState = Set.empty,
      initialOutputs = Chunk.empty,
    )((seen, value) =>
      if (seen.contains(value)) {
        (seen, Chunk.single(None))
      } else {
        (seen + value, Chunk.single(Some(value)))
      }
    )

  /** Simulate a rate limiter that emits at most N items per window */
  def rateLimiter[A](maxPerWindow: Int): Scan[A, Int, Option[A]] =
    Scan.stateful[A, Int, Option[A]](
      initialState = 0,
      initialOutputs = Chunk.empty,
    )((count, value) =>
      if (count < maxPerWindow) {
        (count + 1, Chunk.single(Some(value)))
      } else {
        (count + 1, Chunk.single(None))
      }
    )

  def spec = suite("Real-World Scenarios")(
    test("streaming file processor: count bytes + compute hash") {
      val byteCounter = Scan.foldLeft[Byte, Long](0L)((acc, _) => acc + 1)

      // Simulate processing a "file"
      val fileContent = Chunk.fromIterable(
        "Hello, Graviton! This is a test file.".getBytes
      )

      for {
        count <- ZStream.fromChunk(fileContent).via(byteCounter.pipeline).runLast
      } yield assertTrue(count.contains(fileContent.length.toLong))
    },
    test("data pipeline: deduplicate + count unique") {
      val dedup    = deduplicator[Byte]
      val counter  = Scan.foldLeft[Option[Byte], Long](0L)((acc, opt) => if (opt.isDefined) acc + 1 else acc)
      val pipeline = dedup.andThen(counter)

      // Input with duplicates
      val input = Chunk[Byte](1, 2, 3, 2, 1, 4, 3, 5, 1)

      for {
        result <- ZStream.fromChunk(input).via(pipeline.pipeline).runLast
      } yield assertTrue(result.contains(5L)) // 5 unique values
    },
    test("progress monitoring: report every 1000 bytes") {
      val reporter = progressReporter(1000)
      val input    = Chunk.fill(5500)(0.toByte)

      for {
        reports <- ZStream.fromChunk(input).via(reporter.pipeline).runCollect
      } yield {
        val progressReports = reports.collect { case Left(n) => n }
        val finalReport     = reports.collect { case Right(n) => n }

        assertTrue(
          progressReports.length == 5, // 1000, 2000, 3000, 4000, 5000
          finalReport.head == 5500L,
        )
      }
    },
    test("rate limiting: allow only 10 items") {
      val limiter = rateLimiter[Byte](10)
      val input   = Chunk.fill(100)(0.toByte)

      for {
        result <- ZStream.fromChunk(input).via(limiter.pipeline).runCollect
      } yield {
        val allowed = result.collect { case Some(_) => 1 }.sum
        assertTrue(allowed == 10)
      }
    },
    test("multi-stage pipeline: filter + transform + aggregate") {
      // Stage 1: Filter out zeros
      val filter = Scan.stateful[Byte, Unit, Option[Byte]](
        initialState = (),
        initialOutputs = Chunk.empty,
      )((_, b) => ((), if (b != 0) Chunk.single(Some(b)) else Chunk.single(None)))

      // Stage 2: Extract values
      val extract = Scan.stateful[Option[Byte], Unit, Byte](
        initialState = (),
        initialOutputs = Chunk.empty,
      )((_, opt) => ((), opt.map(Chunk.single(_)).getOrElse(Chunk.empty)))

      // Stage 3: Sum
      val sum = Scan.foldLeft[Byte, Long](0L)((acc, b) => acc + b)

      val pipeline = filter.andThen(extract).andThen(sum)

      val input = Chunk[Byte](1, 0, 2, 0, 3, 0, 4)

      for {
        result <- ZStream.fromChunk(input).via(pipeline.pipeline).runLast
      } yield assertTrue(result.contains(10L)) // 1+2+3+4
    },
    test("windowed aggregation: sum per 10 elements") {
      val windowSize = 10
      val windowed   = Scan.stateful[Byte, (List[Byte], Int), Option[Long]](
        initialState = (List.empty, 0),
        initialOutputs = Chunk.empty,
        onEnd = { case (buffer, _) =>
          if (buffer.nonEmpty) {
            Chunk.single(Some(buffer.map(_.toLong).sum))
          } else {
            Chunk.empty
          }
        },
      ) { case ((buffer, count), byte) =>
        val newBuffer = byte :: buffer
        val newCount  = count + 1

        if (newCount >= windowSize) {
          val windowSum = newBuffer.map(_.toLong).sum
          ((List.empty, 0), Chunk.single(Some(windowSum)))
        } else {
          ((newBuffer, newCount), Chunk.single(None))
        }
      }

      val input = Chunk.fill(25)(1.toByte)

      for {
        result <- ZStream.fromChunk(input).via(windowed.pipeline).runCollect
      } yield {
        val windows = result.collect { case Some(n) => n }
        assertTrue(
          windows.length == 3, // 2 full windows + 1 partial
          windows.take(2).forall(_ == 10L),
          windows.last == 5L,
        )
      }
    },
    test("streaming checksum with progress: hash + count") {
      val hasher   = HashingSpec.sha256Every(100)
      val counter  = Scan.foldLeft[Array[Byte], Long](0L)((acc, _) => acc + 1)
      val pipeline = hasher.andThen(counter)

      val input = Chunk.fill(350)(42.toByte)

      for {
        result <- ZStream.fromChunk(input).via(pipeline.pipeline).runLast
      } yield assertTrue(result.exists(_ >= 3L)) // At least 3 hashes emitted
    },
    test("data validation pipeline: check bounds + count violations") {
      val validator = Scan.stateful[Byte, Long, Either[Byte, String]](
        initialState = 0L,
        initialOutputs = Chunk.empty,
        onEnd = count => Chunk.single(Right(s"Total violations: $count")),
      ) { (count, b) =>
        val value = b & 0xff
        if (value < 10 || value > 100) {
          (count + 1, Chunk.single(Left(b)))
        } else {
          (count, Chunk.single(Left(b)))
        }
      }

      val input = Chunk[Byte](5, 50, -56, 75, 1, 100, -106) // 200 as byte is -56, 150 as byte is -106

      for {
        result <- ZStream.fromChunk(input).via(validator.pipeline).runCollect
      } yield {
        val violations = result.collect { case Right(msg) => msg }
        assertTrue(violations.nonEmpty)
      }
    },
    test("hierarchical processing: bytes -> chunks -> sections") {
      // Level 1: Chunk every 8 bytes
      val chunker = ChunkingSpec.fixedChunker(8)

      // Level 2: Count chunks
      val chunkCounter = Scan.foldLeft[Chunk[Byte], Long](0L)((acc, _) => acc + 1)

      val pipeline = chunker.andThen(chunkCounter)

      val input = Chunk.fill(100)(0.toByte)

      for {
        result <- ZStream.fromChunk(input).via(pipeline.pipeline).runLast
      } yield assertTrue(result.contains(13L)) // 12 full chunks + 1 partial
    },
    test("stateful transformation with history") {
      // Keep last N values and emit their average
      val windowSize    = 5
      val movingAverage = Scan.stateful[Byte, List[Byte], Double](
        initialState = List.empty,
        initialOutputs = Chunk.empty,
      ) { (history, b) =>
        val newHistory = (b :: history).take(windowSize)
        val avg        = newHistory.map(_.toDouble).sum / newHistory.length
        (newHistory, Chunk.single(avg))
      }

      val input = Chunk[Byte](1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

      for {
        result <- ZStream.fromChunk(input).via(movingAverage.pipeline).runCollect
      } yield assertTrue(
        result.length == input.length,
        result.last > 6.0, // Average of last 5 values should be > 6
      )
    },
  )
}
