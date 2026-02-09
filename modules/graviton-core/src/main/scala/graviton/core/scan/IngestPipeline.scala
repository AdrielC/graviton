package graviton.core.scan

import graviton.core.bytes.*
import kyo.Record
import kyo.Record.`~`
import zio.{Chunk, ChunkBuilder}

/**
 * Chunk-level transducers for the CAS ingest pipeline.
 *
 * Element type is always `Chunk[Byte]`. Memory is O(blockSize), never O(stream).
 *
 * All transducers use `Hot` state (primitives) on the hot path and only construct
 * `kyo.Record` summaries at flush boundaries. This means `>>>` composition is
 * '''zero-overhead''' — the composed hot state is a tuple of primitives.
 */
object IngestPipeline:

  /** Count total bytes. Pass-through. Hot = Long. */
  val countBytes: Transducer[Chunk[Byte], Chunk[Byte], Record["totalBytes" ~ Long]] =
    type S = Record["totalBytes" ~ Long]
    new Transducer[Chunk[Byte], Chunk[Byte], S]:
      type Hot = Long
      def initHot: Long                                                 = 0L
      def step(h: Long, chunk: Chunk[Byte]): (Long, Chunk[Chunk[Byte]]) =
        (h + chunk.length.toLong, Chunk.single(chunk))
      def flush(h: Long): (Long, Chunk[Chunk[Byte]])                    = (h, Chunk.empty)
      def toSummary(h: Long): S                                         = (Record.empty & ("totalBytes" ~ h)).asInstanceOf[S]
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
    new Transducer[Chunk[Byte], Chunk[Byte], S]:
      type Hot = (Either[String, Hasher], Long)
      def initHot: Hot                                                = (Hasher.hasher(algo, None), 0L)
      def step(h: Hot, chunk: Chunk[Byte]): (Hot, Chunk[Chunk[Byte]]) =
        h._1.foreach { hasher =>
          val _ = hasher.update(chunk.toArray)
        }
        ((h._1, h._2 + chunk.length.toLong), Chunk.single(chunk))
      def flush(h: Hot): (Hot, Chunk[Chunk[Byte]])                    = (h, Chunk.empty)
      def toSummary(h: Hot): S                                        =
        val hex = h._1.flatMap(_.digest).fold(_ => "", _.hex.value)
        (Record.empty & ("digestHex" ~ hex) & ("hashBytes" ~ h._2)).asInstanceOf[S]
      override def stepChunk(h: Hot, chunks: Chunk[Chunk[Byte]])      =
        var total = h._2
        var idx   = 0
        while idx < chunks.length do
          val c = chunks(idx)
          h._1.foreach { hasher =>
            val _ = hasher.update(c.toArray)
          }
          total += c.length.toLong
          idx += 1
        ((h._1, total), chunks)

  /** Fixed-size rechunker. Hot = (Array, fill, blockCount). */
  def rechunk(
    blockSize: Int
  ): Transducer[Chunk[Byte], Chunk[Byte], Record[("blockCount" ~ Long) & ("rechunkFill" ~ Int)]] =
    type S = Record[("blockCount" ~ Long) & ("rechunkFill" ~ Int)]
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
        (Record.empty & ("blockCount" ~ h._3) & ("rechunkFill" ~ h._2)).asInstanceOf[S]
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
   * **The full CAS ingest pipeline**: count + hash + rechunk via `>>>`.
   *
   * Hot state: `((Long, (Either[String, Hasher], Long)), (Array[Byte], Int, Long))`
   * — all primitives/arrays, '''zero Record allocations in the loop'''.
   *
   * Summary: `Record[(totalBytes ~ Long) & (digestHex ~ String) & (hashBytes ~ Long) & (blockCount ~ Long) & (rechunkFill ~ Int)]`
   * — all fields accessible by name, constructed ONCE at flush.
   */
  def countHashRechunk(
    blockSize: Int,
    algo: HashAlgo = HashAlgo.runtimeDefault,
  ) =
    countBytes >>> hashBytes(algo) >>> rechunk(blockSize)

end IngestPipeline
