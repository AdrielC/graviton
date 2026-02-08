package graviton.core.scan

import graviton.core.bytes.*
import kyo.Record
import kyo.Record.`~`
import zio.{Chunk, ChunkBuilder}

/**
 * Chunk-level transducers for the ingest pipeline.
 *
 * **Critical design rule**: the element type is always `Chunk[Byte]`, never `Byte`.
 * This matches ZIO Streams' internal chunking and ensures:
 *   - No per-byte iteration overhead
 *   - No arbitrary memory collection
 *   - O(blockSize) bounded memory for the rechunker
 *   - Clean composition via `>>>` and `&&&`
 *
 * Pass-through transducers (counting, hashing) observe each chunk and return it
 * unchanged. The rechunker buffers into bounded blocks. All compose cleanly
 * because `step` returns `Chunk.single(chunk)` for pass-throughs, so the `>>>`
 * composition loop body executes exactly once per upstream chunk.
 */
object IngestPipeline:

  // ---------------------------------------------------------------------------
  //  Byte counting (pass-through)
  // ---------------------------------------------------------------------------

  /**
   * Count total bytes flowing through. Pass-through: outputs == inputs.
   *
   * Memory: O(1) — just a counter.
   */
  val countBytes: Transducer[Chunk[Byte], Chunk[Byte], Record["totalBytes" ~ Long]] =
    type S = Record["totalBytes" ~ Long]
    Transducer.fold1[Chunk[Byte], Chunk[Byte], S](
      (Record.empty & ("totalBytes" ~ 0L)).asInstanceOf[S]
    ) { (state, chunk) =>
      val next = (Record.empty & ("totalBytes" ~ (state.totalBytes + chunk.length.toLong))).asInstanceOf[S]
      (next, chunk) // pass-through: output == input
    }(s => (s, Chunk.empty))

  // ---------------------------------------------------------------------------
  //  Incremental hashing (pass-through)
  // ---------------------------------------------------------------------------

  /**
   * Incrementally hash all bytes flowing through. Pass-through: outputs == inputs.
   *
   * Summary fields:
   *   - `hashBytes`: total bytes hashed so far
   *   - `digestHex`: hex string of final digest (empty string until flush)
   *
   * The `digestHex` field is only meaningful after flush (end-of-stream).
   * Use `digestResult` on the return value of `runChunk` or `toSink` for the
   * typed `Either[String, Digest]`.
   *
   * Memory: O(1) — just the hasher state (MessageDigest internals).
   */
  def hashBytes(
    algo: HashAlgo = HashAlgo.runtimeDefault
  ): HashBytesTransducer =
    new HashBytesTransducer(algo)

  /** Wrapper to expose `digestResult` alongside the Record summary. */
  final class HashBytesTransducer(algo: HashAlgo)
      extends Transducer[Chunk[Byte], Chunk[Byte], Record[("digestHex" ~ String) & ("hashBytes" ~ Long)]]:
    type S = Record[("digestHex" ~ String) & ("hashBytes" ~ Long)]

    // Mutable hasher — NOT stored in Record (Java object, no kyo.Tag)
    private var hasher: Either[String, Hasher] = scala.compiletime.uninitialized
    private var total: Long                    = scala.compiletime.uninitialized

    private def mkStepSummary: S =
      (Record.empty & ("digestHex" ~ "") & ("hashBytes" ~ total)).asInstanceOf[S]

    private def mkFlushSummary: S =
      val hex = hasher.flatMap(_.digest).fold(_ => "", _.hex.value)
      (Record.empty & ("digestHex" ~ hex) & ("hashBytes" ~ total)).asInstanceOf[S]

    def init: S =
      hasher = Hasher.hasher(algo, None)
      total = 0L
      mkStepSummary

    def step(s: S, chunk: Chunk[Byte]): (S, Chunk[Chunk[Byte]]) =
      hasher.foreach { h =>
        val _ = h.update(chunk.toArray)
      }
      total += chunk.length.toLong
      (mkStepSummary, Chunk.single(chunk))

    def flush(s: S): (S, Chunk[Chunk[Byte]]) =
      (mkFlushSummary, Chunk.empty)

    override def stepChunk(s: S, chunks: Chunk[Chunk[Byte]]): (S, Chunk[Chunk[Byte]]) =
      var idx = 0
      while idx < chunks.length do
        val c = chunks(idx)
        hasher.foreach { h =>
          val _ = h.update(c.toArray)
        }
        total += c.length.toLong
        idx += 1
      (mkStepSummary, chunks)

    /** Get the typed digest result (call after `runChunk` or on the `toSink` summary). */
    def digestResult: Either[String, Digest] =
      hasher.flatMap(_.digest)

  // ---------------------------------------------------------------------------
  //  Fixed-size rechunker (bounded buffer)
  // ---------------------------------------------------------------------------

  /**
   * Rechunk variable-size `Chunk[Byte]` inputs into fixed-size blocks.
   *
   * Input:  `Chunk[Byte]` of any size (from upstream / previous transducer)
   * Output: `Chunk[Byte]` of exactly `blockSize` bytes (except last block on flush)
   *
   * Memory: O(blockSize) — exactly one block buffer, never more.
   * The buffer is a fixed-size `Array[Byte]` allocated once.
   *
   * This is the rechunking equivalent of `ChunkerCore.Mode.Fixed` but
   * operating at the `Chunk[Byte]` element level for clean composition.
   */
  def rechunk(
    blockSize: Int
  ): Transducer[Chunk[Byte], Chunk[Byte], Record[("blockCount" ~ Long) & ("rechunkFill" ~ Int)]] =
    type S = Record[("blockCount" ~ Long) & ("rechunkFill" ~ Int)]
    val safeSize = math.max(1, math.min(blockSize, 16 * 1024 * 1024))

    new Transducer[Chunk[Byte], Chunk[Byte], S]:
      private var buf: Array[Byte] = scala.compiletime.uninitialized
      private var fill: Int        = scala.compiletime.uninitialized
      private var count: Long      = scala.compiletime.uninitialized

      private def mkSummary: S =
        (Record.empty & ("blockCount" ~ count) & ("rechunkFill" ~ fill)).asInstanceOf[S]

      def init: S =
        buf = Array.ofDim[Byte](safeSize)
        fill = 0
        count = 0L
        mkSummary

      def step(s: S, chunk: Chunk[Byte]): (S, Chunk[Chunk[Byte]]) =
        if chunk.isEmpty then (mkSummary, Chunk.empty)
        else
          val out = ChunkBuilder.make[Chunk[Byte]]()
          var idx = 0
          while idx < chunk.length do
            val space     = safeSize - fill
            val available = chunk.length - idx
            val toCopy    = math.min(space, available)

            // Copy toCopy bytes from chunk[idx..] into buf[fill..]
            var j = 0
            while j < toCopy do
              buf(fill + j) = chunk(idx + j)
              j += 1
            fill += toCopy
            idx += toCopy

            // Emit block if buffer is full
            if fill >= safeSize then
              out += Chunk.fromArray(java.util.Arrays.copyOf(buf, safeSize))
              count += 1
              fill = 0
          end while
          (mkSummary, out.result())

      def flush(s: S): (S, Chunk[Chunk[Byte]]) =
        if fill > 0 then
          val remainder = Chunk.fromArray(java.util.Arrays.copyOf(buf, fill))
          val summary   = mkSummary
          fill = 0
          (summary, Chunk.single(remainder))
        else (mkSummary, Chunk.empty)

      override def stepChunk(s: S, chunks: Chunk[Chunk[Byte]]): (S, Chunk[Chunk[Byte]]) =
        val out = ChunkBuilder.make[Chunk[Byte]]()
        var ci  = 0
        while ci < chunks.length do
          val chunk = chunks(ci)
          var idx   = 0
          while idx < chunk.length do
            val space     = safeSize - fill
            val available = chunk.length - idx
            val toCopy    = math.min(space, available)
            System.arraycopy(chunk.toArray, idx, buf, fill, toCopy)
            fill += toCopy
            idx += toCopy
            if fill >= safeSize then
              out += Chunk.fromArray(java.util.Arrays.copyOf(buf, safeSize))
              count += 1
              fill = 0
          end while
          ci += 1
        (mkSummary, out.result())

  // ---------------------------------------------------------------------------
  //  The composed pipeline
  // ---------------------------------------------------------------------------

  /**
   * **The full ingest pipeline**: count bytes + hash + rechunk, all in one composed scan.
   *
   * ```
   * Chunk[Byte] → countBytes → hashBytes → rechunk(blockSize) → Chunk[Byte] (fixed blocks)
   * ```
   *
   * Type of composed summary:
   * ```
   * Record[
   *   ("totalBytes" ~ Long) &
   *   ("digest" ~ Either[String, Digest]) &
   *   ("hashBytes" ~ Long) &
   *   ("blockCount" ~ Long) &
   *   ("rechunkFill" ~ Int)
   * ]
   * ```
   *
   * All fields accessible by name on the summary after `runChunk`, `toSink`, or `summarize`.
   *
   * Memory: O(blockSize) — counting is O(1), hashing is O(1), rechunking is O(blockSize).
   * The pipeline NEVER collects arbitrarily-sized data into memory.
   *
   * @param blockSize  Target block size in bytes (max 16 MiB)
   * @param algo       Hash algorithm (default: runtime-detected best)
   */
  def countHashRechunk(
    blockSize: Int,
    algo: HashAlgo = HashAlgo.runtimeDefault,
  ) =
    countBytes >>> hashBytes(algo) >>> rechunk(blockSize)

end IngestPipeline
