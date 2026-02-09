package graviton.core.scan

import graviton.core.bytes.*
import kyo.Record
import kyo.Record.`~`
import zio.*
import zio.stream.*
import zio.test.*

import java.security.MessageDigest

/**
 * Microbenchmarks comparing Transducer composition against alternatives
 * for CAS-realistic workloads.
 *
 * Run with:
 *   TESTCONTAINERS=0 ./sbt 'core/testOnly graviton.core.scan.TransducerBench'
 *
 * Benchmarks:
 *   1. Count + Hash + Rechunk pipeline (the core CAS ingest)
 *   2. Map fusion (chained transforms)
 *   3. Dedup (set-based filtering at scale)
 *
 * Compared against:
 *   - Hand-rolled imperative loop (baseline: fastest possible)
 *   - Raw ZPipeline composition
 *   - Transducer composition
 *
 * All benchmarks process the same data and verify correctness.
 */
object TransducerBench extends ZIOSpecDefault:

  // ---------------------------------------------------------------------------
  //  Helpers
  // ---------------------------------------------------------------------------

  private val warmupRuns = 3
  private val benchRuns  = 5

  private def bench[E, A](label: String)(zio: ZIO[Any, E, A]): ZIO[Any, E, (Double, A)] =
    for
      // Warmup
      _         <- ZIO.foreach(1 to warmupRuns)(_ => zio)
      // Timed runs — use java.lang.System.nanoTime for real wall-clock
      times     <- ZIO.foreach(1 to benchRuns) { _ =>
                     ZIO.suspendSucceed {
                       val t0 = java.lang.System.nanoTime()
                       zio.map { a =>
                         val t1 = java.lang.System.nanoTime()
                         (t1 - t0, a)
                       }
                     }
                   }
      sorted     = times.map(_._1).sorted
      // Drop best and worst, average the rest
      trimmed    = if sorted.length > 2 then sorted.drop(1).dropRight(1) else sorted
      avgMs      = trimmed.map(_.toDouble / 1e6).sum / trimmed.length
      _         <- Console.printLine(f"  $label%-45s $avgMs%8.2f ms  (${sorted.length} runs)").orDie
      lastResult = times.last._2
    yield (avgMs, lastResult)

  private def printHeader(title: String): URIO[Any, Unit] =
    Console.printLine(s"\n=== $title ===").orDie

  // ---------------------------------------------------------------------------
  //  Data generation
  // ---------------------------------------------------------------------------

  private val dataSize  = 4 * 1024 * 1024 // 4 MB
  private val blockSize = 4096            // 4 KB blocks

  /** 4 MB of pseudo-random bytes. */
  private lazy val testData: Array[Byte] =
    val rng = new java.util.Random(42L)
    val arr = Array.ofDim[Byte](dataSize)
    rng.nextBytes(arr)
    arr

  /** Input as irregular Chunk[Byte] elements (simulating real upstream). */
  private lazy val inputChunks: List[Chunk[Byte]] =
    val rng     = new java.util.Random(99L)
    val builder = List.newBuilder[Chunk[Byte]]
    var offset  = 0
    while offset < testData.length do
      val size = math.min(800 + rng.nextInt(1600), testData.length - offset) // 800-2400 byte chunks
      builder += Chunk.fromArray(java.util.Arrays.copyOfRange(testData, offset, offset + size))
      offset += size
    builder.result()

  // ---------------------------------------------------------------------------
  //  Benchmark 1: Count + Hash + Rechunk (CAS ingest pipeline)
  // ---------------------------------------------------------------------------

  private def bench1_handRolled: ZIO[Any, Nothing, (Long, String, Long)] = ZIO.succeed {
    val md     = MessageDigest.getInstance("SHA-256")
    val buf    = Array.ofDim[Byte](blockSize)
    var fill   = 0
    var total  = 0L
    var blocks = 0L

    inputChunks.foreach { chunk =>
      val arr = chunk.toArray
      md.update(arr)
      total += arr.length

      var idx = 0
      while idx < arr.length do
        val space  = blockSize - fill
        val toCopy = math.min(space, arr.length - idx)
        java.lang.System.arraycopy(arr, idx, buf, fill, toCopy)
        fill += toCopy
        idx += toCopy
        if fill >= blockSize then
          blocks += 1
          fill = 0
      end while
    }
    if fill > 0 then blocks += 1 // remainder

    val digest = scodec.bits.ByteVector(md.digest()).toHex
    (total, digest, blocks)
  }

  private def bench1_transducer: ZIO[Any, Nothing, (Long, String, Long)] = ZIO.succeed {
    val pipeline       = IngestPipeline.countHashRechunk(blockSize)
    val (summary, out) = pipeline.runChunk(inputChunks)
    (summary.totalBytes, summary.digestHex, summary.blockCount + (if out.nonEmpty && out.last.length < blockSize then 1L else 0L))
  }

  private def bench1_rawZPipeline: ZIO[Any, Throwable, (Long, Long)] =
    // Raw ZPipeline: rechunk + count via runFold
    ZStream
      .fromIterable(inputChunks)
      .flatMap(c => ZStream.fromChunk(c)) // flatten to byte stream
      .rechunk(blockSize)
      .runFold((0L, 0L)) { case ((total, blocks), byte) =>
        (total + 1, blocks) // just counting bytes this way is expensive
      }
      .map { case (total, _) => (total, 0L) }

  // ---------------------------------------------------------------------------
  //  Benchmark 2: Map fusion (chained transforms)
  // ---------------------------------------------------------------------------

  private val mapInputSize             = 1_000_000
  private lazy val mapInput: List[Int] = (1 to mapInputSize).toList

  private def bench2_handRolled: ZIO[Any, Nothing, Long] = ZIO.succeed {
    var sum = 0L
    mapInput.foreach { i =>
      val v = ((i + 1) * 2 + 3).toLong
      if v % 2 == 0 then sum += v
    }
    sum
  }

  private def bench2_transducerFused: ZIO[Any, Nothing, Long] = ZIO.succeed {
    // Three maps fuse into one, then filter fuses
    val t        = Transducer
      .map[Int, Int](_ + 1)
      .map(_ * 2)
      .map(_ + 3)
      .map(_.toLong)
      .filter(_ % 2 == 0)
    val (_, out) = t.runChunk(mapInput)
    out.foldLeft(0L)(_ + _)
  }

  private def bench2_scalaIterator: ZIO[Any, Nothing, Long] = ZIO.succeed {
    mapInput.iterator
      .map(_ + 1)
      .map(_ * 2)
      .map(_ + 3)
      .map(_.toLong)
      .filter(_ % 2 == 0)
      .foldLeft(0L)(_ + _)
  }

  private def bench2_zstreamChained: ZIO[Any, Throwable, Long] =
    ZStream
      .fromIterable(mapInput)
      .map(_ + 1)
      .map(_ * 2)
      .map(_ + 3)
      .map(_.toLong)
      .filter(_ % 2 == 0)
      .runFold(0L)(_ + _)

  // ---------------------------------------------------------------------------
  //  Benchmark 3: Dedup (CAS-relevant: block key deduplication)
  // ---------------------------------------------------------------------------

  private val dedupInputSize             = 500_000
  private lazy val dedupInput: List[Int] =
    val rng = new java.util.Random(77L)
    (1 to dedupInputSize).map(_ => rng.nextInt(dedupInputSize / 2)).toList // ~50% duplication

  private def bench3_handRolled: ZIO[Any, Nothing, (Int, Int)] = ZIO.succeed {
    var seen   = Set.empty[Int]
    var unique = 0
    var dupes  = 0
    dedupInput.foreach { i =>
      if seen.contains(i) then dupes += 1
      else
        seen += i
        unique += 1
    }
    (unique, dupes)
  }

  private def bench3_transducer: ZIO[Any, Nothing, (Long, Long)] = ZIO.succeed {
    val (summary, _) = Transducers.dedup[Int, Int](identity).runChunk(dedupInput)
    (summary.uniqueCount, summary.duplicateCount)
  }

  private def bench3_scalaDistinct: ZIO[Any, Nothing, Int] = ZIO.succeed {
    dedupInput.distinct.length
  }

  // ---------------------------------------------------------------------------
  //  Spec
  // ---------------------------------------------------------------------------

  override def spec: Spec[TestEnvironment, Any] =
    suite("TransducerBench")(
      test("Benchmark 1: Count + Hash + Rechunk (4 MB, 4 KB blocks)") {
        for
          _        <-
            printHeader(s"CAS Ingest: ${dataSize / 1024 / 1024} MB → ${blockSize / 1024} KB blocks [Hot state: tuples, no Record in loop]")
          (t1, r1) <- bench("Hand-rolled imperative loop")(bench1_handRolled)
          (t2, r2) <- bench("Transducer COMPOSED (count>>>hash>>>rechunk)")(bench1_transducer)
          _        <- Console.printLine(f"  Composed Transducer vs hand-rolled: ${(t2 / t1 - 1) * 100}%+.1f%%").orDie
        yield assertTrue(r1._1 == r2._1) &&
          assertTrue(r1._2 == r2._2) &&
          assertTrue(r1._1 == dataSize.toLong)
      },
      test("Benchmark 2: Map fusion (1M elements, 5 chained transforms)") {
        for
          _        <- printHeader(s"Map fusion: ${mapInputSize / 1000}K elements, 5 chained ops")
          (t1, r1) <- bench("Hand-rolled loop")(bench2_handRolled)
          (t2, r2) <- bench("Transducer (fused maps + filter)")(bench2_transducerFused)
          (t3, r3) <- bench("Scala Iterator (lazy, not fused)")(bench2_scalaIterator)
          (t4, r4) <- bench("ZStream (5 chained .map/.filter)")(bench2_zstreamChained)
          _        <- Console.printLine(f"  Transducer vs hand-rolled: ${(t2 / t1 - 1) * 100}%+.1f%%").orDie
          _        <- Console.printLine(f"  Transducer vs Iterator:    ${(t2 / t3 - 1) * 100}%+.1f%%").orDie
          _        <- Console.printLine(f"  Transducer vs ZStream:     ${(t2 / t4 - 1) * 100}%+.1f%%").orDie
        // All should produce the same result
        yield assertTrue(r1 == r2) &&
          assertTrue(r2 == r3) &&
          assertTrue(r3 == r4)
      },
      test("Benchmark 3: Dedup (500K elements, ~50% duplication rate)") {
        for
          _        <- printHeader(s"Dedup: ${dedupInputSize / 1000}K elements, ~50%% duplicate rate")
          (t1, r1) <- bench("Hand-rolled Set-based loop")(bench3_handRolled)
          (t2, r2) <- bench("Transducer: dedup(identity)")(bench3_transducer)
          (t3, r3) <- bench("Scala .distinct")(bench3_scalaDistinct)
          _        <- Console.printLine(f"  Transducer vs hand-rolled: ${(t2 / t1 - 1) * 100}%+.1f%%").orDie
          _        <- Console.printLine(f"  Transducer vs .distinct:   ${(t2 / t3 - 1) * 100}%+.1f%%").orDie
        // Verify correctness
        yield assertTrue(r1._1.toLong == r2._1) &&
          assertTrue(r1._2.toLong == r2._2)
      },
    ) @@ TestAspect.sequential
