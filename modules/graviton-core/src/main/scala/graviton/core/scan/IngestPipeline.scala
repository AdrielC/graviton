package graviton.core.scan

import graviton.core.bytes.*
import kyo.Record
import kyo.Record.`~`
import zio.{Chunk, ChunkBuilder}
import zio.blocks.schema.Schema

/**
 * Chunk-level transducers for the CAS ingest pipeline.
 *
 * Element type is always `Chunk[Byte]`. The transducer state is O(blockSize).
 * Streaming compilation through `toPipeline` or `toChannel` does not retain all
 * outputs, while `runChunk` and `toSink` intentionally collect them and therefore
 * require an independently bounded input.
 *
 * All transducers use `Hot` state (primitives) on the hot path and construct
 * summaries only at terminal boundaries. Individual stages expose compact
 * `kyo.Record` summaries. [[countHashRechunkSummary]] maps the aggregate result
 * to the explicit, schema-backed [[Summary]] type, avoiding dynamic Record
 * access. [[countHashRechunk]] retains the v0.7 Record-shaped ABI for existing
 * binaries.
 * The composed hot state is a tuple of primitives; throughput and allocation
 * claims still require measurement.
 */
object IngestPipeline:

  /** Stable, schema-backed terminal summary for the composed ingest pipeline. */
  final case class Summary(
    totalBytes: Long,
    digestHex: String,
    hashBytes: Long,
    blockCount: Long,
    rechunkFill: Int,
  )

  object Summary:
    given Schema[Summary] = Schema.derived

  private final case class CountSummary(totalBytes: Long)
  private final case class HashSummary(digestHex: String, hashBytes: Long)
  private final case class RechunkSummary(blockCount: Long, rechunkFill: Int)

  /** Count total bytes. Pass-through. Hot = Long. */
  val countBytes: Transducer[Chunk[Byte], Chunk[Byte], Record["totalBytes" ~ Long]] =
    type S = Record["totalBytes" ~ Long]
    countBytesWithSummary(h => (Record.empty & ("totalBytes" ~ h)).asInstanceOf[S])

  private def countBytesWithSummary[S](summarize: Long => S): Transducer[Chunk[Byte], Chunk[Byte], S] =
    new Transducer[Chunk[Byte], Chunk[Byte], S]:
      type Hot = Long
      def initHot: Long                                                 = 0L
      def step(h: Long, chunk: Chunk[Byte]): (Long, Chunk[Chunk[Byte]]) =
        (h + chunk.length.toLong, Chunk.single(chunk))
      def flush(h: Long): (Long, Chunk[Chunk[Byte]])                    = (h, Chunk.empty)
      def toSummary(h: Long): S                                         = summarize(h)
      override def stepChunk(h: Long, chunks: Chunk[Chunk[Byte]])       =
        var total = h
        var idx   = 0
        while idx < chunks.length do
          total += chunks(idx).length.toLong
          idx += 1
        (total, chunks)

  /** Incremental hash. Pass-through. Hot = (Hasher, Long). */
  def hashBytes(
    algo: HashAlgo = HashAlgo.runtimeDefault
  ): Transducer[Chunk[Byte], Chunk[Byte], Record[("digestHex" ~ String) & ("hashBytes" ~ Long)]] =
    type S = Record[("digestHex" ~ String) & ("hashBytes" ~ Long)]
    hashBytesWithSummary(algo)((hex, bytes) => (Record.empty & ("digestHex" ~ hex) & ("hashBytes" ~ bytes)).asInstanceOf[S])

  private def hashBytesWithSummary[S](
    algo: HashAlgo
  )(summarize: (String, Long) => S): Transducer[Chunk[Byte], Chunk[Byte], S] =
    new Transducer[Chunk[Byte], Chunk[Byte], S]:
      type Hot = (Either[String, Hasher], Long)
      def initHot: Hot                                                = (Hasher.hasher(algo, None), 0L)
      def step(h: Hot, chunk: Chunk[Byte]): (Hot, Chunk[Chunk[Byte]]) =
        h._1.foreach { hasher =>
          val _ = hasher.update(chunk)
        }
        ((h._1, h._2 + chunk.length.toLong), Chunk.single(chunk))
      def flush(h: Hot): (Hot, Chunk[Chunk[Byte]])                    = (h, Chunk.empty)
      def toSummary(h: Hot): S                                        =
        val hex = h._1.flatMap(_.digest).fold(_ => "", _.hex.value)
        summarize(hex, h._2)
      override def stepChunk(h: Hot, chunks: Chunk[Chunk[Byte]])      =
        var total = h._2
        var idx   = 0
        while idx < chunks.length do
          val c = chunks(idx)
          h._1.foreach { hasher =>
            val _ = hasher.update(c)
          }
          total += c.length.toLong
          idx += 1
        ((h._1, total), chunks)

  /** Fixed-size rechunker. Hot = (Array, fill, blockCount). */
  def rechunk(
    blockSize: Int
  ): Transducer[Chunk[Byte], Chunk[Byte], Record[("blockCount" ~ Long) & ("rechunkFill" ~ Int)]] =
    type S = Record[("blockCount" ~ Long) & ("rechunkFill" ~ Int)]
    rechunkWithSummary(blockSize)((count, fill) => (Record.empty & ("blockCount" ~ count) & ("rechunkFill" ~ fill)).asInstanceOf[S])

  private def rechunkWithSummary[S](
    blockSize: Int
  )(summarize: (Long, Int) => S): Transducer[Chunk[Byte], Chunk[Byte], S] =
    val safeSize = math.max(1, math.min(blockSize, 16 * 1024 * 1024))
    new Transducer[Chunk[Byte], Chunk[Byte], S]:
      type Hot = (Array[Byte], Int, Long) // buf, fill, blockCount
      def initHot: Hot                                                = (Array.ofDim[Byte](safeSize), 0, 0L)
      def step(h: Hot, chunk: Chunk[Byte]): (Hot, Chunk[Chunk[Byte]]) =
        val (buf, fill0, count0) = h
        if chunk.isEmpty then (h, Chunk.empty)
        else
          val out   = ChunkBuilder.make[Chunk[Byte]]()
          var fill  = fill0
          var count = count0
          var idx   = 0
          while idx < chunk.length do
            val space  = safeSize - fill
            val toCopy = math.min(space, chunk.length - idx)
            var j      = 0
            while j < toCopy do
              buf(fill + j) = chunk(idx + j)
              j += 1
            fill += toCopy
            idx += toCopy
            if fill >= safeSize then
              out += Chunk.fromArray(java.util.Arrays.copyOf(buf, safeSize))
              count += 1
              fill = 0
          end while
          ((buf, fill, count), out.result())
      def flush(h: Hot): (Hot, Chunk[Chunk[Byte]])                    =
        val (buf, fill, count) = h
        if fill > 0 then ((buf, 0, count), Chunk.single(Chunk.fromArray(java.util.Arrays.copyOf(buf, fill))))
        else (h, Chunk.empty)
      def toSummary(h: Hot): S                                        =
        summarize(h._3, h._2)
      override def stepChunk(h: Hot, chunks: Chunk[Chunk[Byte]])      =
        val (buf, fill0, count0) = h
        val out                  = ChunkBuilder.make[Chunk[Byte]]()
        var fill                 = fill0
        var count                = count0
        var ci                   = 0
        while ci < chunks.length do
          val chunk = chunks(ci)
          val arr   = chunk.toArray
          var idx   = 0
          while idx < arr.length do
            val space  = safeSize - fill
            val toCopy = math.min(space, arr.length - idx)
            java.lang.System.arraycopy(arr, idx, buf, fill, toCopy)
            fill += toCopy
            idx += toCopy
            if fill >= safeSize then
              out += Chunk.fromArray(java.util.Arrays.copyOf(buf, safeSize))
              count += 1
              fill = 0
          end while
          ci += 1
        ((buf, fill, count), out.result())

  /**
   * The v0.7-compatible count + hash + rechunk pipeline.
   *
   * New code should use [[countHashRechunkSummary]], whose terminal summary is
   * an explicit schema-backed product.
   */
  @deprecated("Use countHashRechunkSummary for an explicit schema-backed summary", "0.8.0")
  def countHashRechunk(
    blockSize: Int,
    algo: HashAlgo = HashAlgo.runtimeDefault,
  ) =
    countBytes >>> hashBytes(algo) >>> rechunk(blockSize)

  /**
   * **The full CAS ingest pipeline**: count + hash + rechunk via `>>>`.
   *
   * Hot state: `((Long, (Either[String, Hasher], Long)), (Array[Byte], Int, Long))`
   * — all primitives/arrays, '''zero Record allocations in the loop'''.
   *
   * Summary: [[Summary]], constructed once at the terminal boundary and backed
   * by a ZIO Blocks schema. It deliberately avoids dynamic record access.
   */
  def countHashRechunkSummary(
    blockSize: Int,
    algo: HashAlgo = HashAlgo.runtimeDefault,
  ): Transducer[Chunk[Byte], Chunk[Byte], Summary] =
    val composed =
      countBytesWithSummary(CountSummary.apply) >>>
        hashBytesWithSummary(algo)(HashSummary.apply) >>>
        rechunkWithSummary(blockSize)(RechunkSummary.apply)

    composed.mapSummary { case ((count, hash), chunks) =>
      Summary(
        totalBytes = count.totalBytes,
        digestHex = hash.digestHex,
        hashBytes = hash.hashBytes,
        blockCount = chunks.blockCount,
        rechunkFill = chunks.rechunkFill,
      )
    }

end IngestPipeline
