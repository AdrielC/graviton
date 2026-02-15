package graviton.core.scan

import graviton.core.bytes.HashAlgo
import zio.*
import zio.test.*

/**
 * Comparative benchmark: tuple-based `IngestPipeline` vs register-based
 * `RegisterIngestPipeline`.
 *
 * Runs `countBytes >>> hashBytes >>> rechunk` over the same data with both
 * implementations and reports wall-clock times.
 *
 * This is not a JMH micro-benchmark — it's a coarse "is it in the same
 * ballpark?" check that runs inside the normal test suite.
 */
object RegisterVsTupleBenchSpec extends ZIOSpecDefault:

  private val blockSize  = 1024 * 1024 // 1 MiB blocks
  private val dataSizeMB = 128
  private val chunkSize  = 64 * 1024   // 64 KiB input chunks
  private val iterations = 5

  /** Generate test data: `dataSizeMB` MB of pseudo-random bytes in 64 KiB chunks. */
  private def generateData: Chunk[Chunk[Byte]] =
    val rng       = new java.util.Random(42L) // deterministic seed
    val numChunks = (dataSizeMB * 1024 * 1024) / chunkSize
    val builder   = ChunkBuilder.make[Chunk[Byte]]()
    var i         = 0
    while i < numChunks do
      val arr = Array.ofDim[Byte](chunkSize)
      rng.nextBytes(arr)
      builder += Chunk.fromArray(arr)
      i += 1
    builder.result()

  private def benchTuple(data: Chunk[Chunk[Byte]]): (Long, Long, String) =
    val pipeline          = IngestPipeline.countHashRechunk(blockSize)
    val start             = java.lang.System.nanoTime()
    val (summary, blocks) = pipeline.runChunk(data)
    val elapsed           = java.lang.System.nanoTime() - start
    (elapsed, blocks.length.toLong, s"tuple: ${elapsed / 1_000_000}ms, ${blocks.length} blocks")

  private def benchRegisters(data: Chunk[Chunk[Byte]]): (Long, Long, String) =
    val pipeline          = RegisterIngestPipeline.countHashRechunk(blockSize)
    val start             = java.lang.System.nanoTime()
    val (summary, blocks) = pipeline.runChunk(data)
    val elapsed           = java.lang.System.nanoTime() - start
    (elapsed, blocks.length.toLong, s"registers: ${elapsed / 1_000_000}ms, ${blocks.length} blocks")

  def spec = suite("RegisterVsTupleBench")(
    test("register-backed and tuple-backed produce same block count") {
      val data = generateData

      // Warm up both paths
      val _ = benchTuple(data)
      val _ = benchRegisters(data)

      // Measured runs
      // Warm up both paths (3 warm-up runs each)
      (1 to 3).foreach { _ =>
        benchTuple(data)
        benchRegisters(data)
      }

      // Measured runs
      val tupleRuns = (1 to iterations).map(_ => benchTuple(data))
      val regRuns   = (1 to iterations).map(_ => benchRegisters(data))

      val tupleMedianNs = tupleRuns.map(_._1).sorted.apply(iterations / 2)
      val regMedianNs   = regRuns.map(_._1).sorted.apply(iterations / 2)
      val tupleBlocks   = tupleRuns.head._2
      val regBlocks     = regRuns.head._2

      val tupleMB = dataSizeMB.toDouble / (tupleMedianNs.toDouble / 1e9)
      val regMB   = dataSizeMB.toDouble / (regMedianNs.toDouble / 1e9)

      println(s"=== Pipeline Benchmark ($dataSizeMB MB, ${blockSize / 1024} KiB blocks, $iterations iterations) ===")
      println(s"  Tuple-based (median):    ${tupleMedianNs / 1_000_000}ms  (${f"$tupleMB%.1f"} MB/s)  blocks=$tupleBlocks")
      println(s"  Register-based (median): ${regMedianNs / 1_000_000}ms  (${f"$regMB%.1f"} MB/s)  blocks=$regBlocks")
      println(s"  Speedup:                 ${f"${tupleMedianNs.toDouble / regMedianNs.toDouble}%.3f"}x")
      println(s"  All tuple times:         ${tupleRuns.map(_._1 / 1_000_000).mkString(", ")} ms")
      println(s"  All register times:      ${regRuns.map(_._1 / 1_000_000).mkString(", ")} ms")
      println(s"===")

      assertTrue(tupleBlocks == regBlocks) &&
      assertTrue(tupleBlocks == dataSizeMB.toLong) // 128 MB / 1 MiB = 128 blocks
    } @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.ignore
  )
