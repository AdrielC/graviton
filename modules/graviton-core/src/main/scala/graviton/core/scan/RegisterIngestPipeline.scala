package graviton.core.scan

import graviton.core.bytes.*
import zio.{Chunk, ChunkBuilder}
import zio.blocks.schema.binding.{Register, RegisterOffset, Registers}
import RegisterOffset.RegisterOffset

/**
 * Register-backed ingest pipeline transducers.
 *
 * Mirrors [[IngestPipeline]] but uses `zio.blocks.schema.binding.Registers`
 * for hot state instead of tuples. This gives:
 *   - Zero boxing for primitives (Long, Int stored in flat byte array)
 *   - Flat composition (one Registers instance per composed pipeline)
 *   - No kyo.Record dependency for summaries
 *
 * This module exists to benchmark the Registers approach against the current
 * tuple-based `Transducer.Hot` pattern.
 */
object RegisterIngestPipeline:

  // -------------------------------------------------------------------------
  //  Register layouts
  // -------------------------------------------------------------------------

  // Register layouts: relativeIndex is a BYTE offset from the base.
  // We must ensure fields don't overlap within the same byte array.
  //
  // RegisterOffset(longs = N) allocates N*8 bytes.
  // RegisterOffset(ints = N) allocates N*4 bytes.
  // RegisterOffset(objects = N) allocates N object slots.
  // The total byte budget is the sum.

  object CountLayout:
    // 1 Long at byte offset 0
    val totalBytes: Register.Long = Register.Long(0)
    val offset: RegisterOffset    = RegisterOffset(longs = 1) // 8 bytes

  object HashLayout:
    // 1 Object (the Hasher) + 1 Long at byte offset 0
    val hasher: Register.Object[Either[String, Hasher]] = Register.Object(0)
    val hashBytes: Register.Long                        = Register.Long(0)
    val offset: RegisterOffset                          = RegisterOffset(objects = 1, longs = 1) // 8 bytes + 1 obj

  object RechunkLayout:
    // 1 Object (the buffer) + 1 Int at byte 0, 1 Long at byte 4
    val buf: Register.Object[Array[Byte]] = Register.Object(0)
    val fill: Register.Int                = Register.Int(0)    // bytes [0..3]
    val blockCount: Register.Long         = Register.Long(4)   // bytes [4..11]
    val offset: RegisterOffset            = RegisterOffset(objects = 1, ints = 1, longs = 1) // 12 bytes + 1 obj

  // -------------------------------------------------------------------------
  //  Individual stages (register-backed)
  // -------------------------------------------------------------------------

  /** Count total bytes. Pass-through. */
  val countBytes: Transducer[Chunk[Byte], Chunk[Byte], Long] =
    new Transducer[Chunk[Byte], Chunk[Byte], Long]:
      type Hot = Registers
      def initHot: Registers =
        val r = Registers(CountLayout.offset)
        CountLayout.totalBytes.set(r, RegisterOffset.Zero, 0L)
        r
      def step(h: Registers, chunk: Chunk[Byte]): (Registers, Chunk[Chunk[Byte]]) =
        val cur = CountLayout.totalBytes.get(h, RegisterOffset.Zero)
        CountLayout.totalBytes.set(h, RegisterOffset.Zero, cur + chunk.length.toLong)
        (h, Chunk.single(chunk))
      def flush(h: Registers): (Registers, Chunk[Chunk[Byte]]) = (h, Chunk.empty)
      def toSummary(h: Registers): Long = CountLayout.totalBytes.get(h, RegisterOffset.Zero)
      override def stepChunk(h: Registers, chunks: Chunk[Chunk[Byte]]): (Registers, Chunk[Chunk[Byte]]) =
        var total = CountLayout.totalBytes.get(h, RegisterOffset.Zero)
        var idx   = 0
        while idx < chunks.length do
          total += chunks(idx).length.toLong
          idx += 1
        CountLayout.totalBytes.set(h, RegisterOffset.Zero, total)
        (h, chunks)

  /** Incremental hash. Pass-through. */
  def hashBytes(
    algo: HashAlgo = HashAlgo.runtimeDefault
  ): Transducer[Chunk[Byte], Chunk[Byte], String] =
    new Transducer[Chunk[Byte], Chunk[Byte], String]:
      type Hot = Registers
      def initHot: Registers =
        val r = Registers(HashLayout.offset)
        HashLayout.hasher.set(r, RegisterOffset.Zero, Hasher.hasher(algo, None).asInstanceOf[AnyRef].asInstanceOf[Either[String, Hasher]])
        HashLayout.hashBytes.set(r, RegisterOffset.Zero, 0L)
        r
      def step(h: Registers, chunk: Chunk[Byte]): (Registers, Chunk[Chunk[Byte]]) =
        val hasherE = HashLayout.hasher.get(h, RegisterOffset.Zero)
        hasherE.foreach(_.update(chunk.toArray))
        val cur = HashLayout.hashBytes.get(h, RegisterOffset.Zero)
        HashLayout.hashBytes.set(h, RegisterOffset.Zero, cur + chunk.length.toLong)
        (h, Chunk.single(chunk))
      def flush(h: Registers): (Registers, Chunk[Chunk[Byte]]) = (h, Chunk.empty)
      def toSummary(h: Registers): String =
        HashLayout.hasher.get(h, RegisterOffset.Zero).flatMap(_.digest).fold(_ => "", _.hex.value)
      override def stepChunk(h: Registers, chunks: Chunk[Chunk[Byte]]): (Registers, Chunk[Chunk[Byte]]) =
        val hasherE = HashLayout.hasher.get(h, RegisterOffset.Zero)
        var total   = HashLayout.hashBytes.get(h, RegisterOffset.Zero)
        var idx     = 0
        while idx < chunks.length do
          val c = chunks(idx)
          hasherE.foreach(_.update(c.toArray))
          total += c.length.toLong
          idx += 1
        HashLayout.hashBytes.set(h, RegisterOffset.Zero, total)
        (h, chunks)

  /** Fixed-size rechunker. */
  def rechunk(blockSize: Int): Transducer[Chunk[Byte], Chunk[Byte], Long] =
    val safeSize = math.max(1, math.min(blockSize, 16 * 1024 * 1024))
    new Transducer[Chunk[Byte], Chunk[Byte], Long]:
      type Hot = Registers
      def initHot: Registers =
        val r = Registers(RechunkLayout.offset)
        RechunkLayout.buf.set(r, RegisterOffset.Zero, Array.ofDim[Byte](safeSize))
        RechunkLayout.fill.set(r, RegisterOffset.Zero, 0)
        RechunkLayout.blockCount.set(r, RegisterOffset.Zero, 0L)
        r
      def step(h: Registers, chunk: Chunk[Byte]): (Registers, Chunk[Chunk[Byte]]) =
        if chunk.isEmpty then (h, Chunk.empty)
        else
          val buf   = RechunkLayout.buf.get(h, RegisterOffset.Zero)
          var fill  = RechunkLayout.fill.get(h, RegisterOffset.Zero)
          var count = RechunkLayout.blockCount.get(h, RegisterOffset.Zero)
          val out   = ChunkBuilder.make[Chunk[Byte]]()
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
          RechunkLayout.fill.set(h, RegisterOffset.Zero, fill)
          RechunkLayout.blockCount.set(h, RegisterOffset.Zero, count)
          (h, out.result())
      def flush(h: Registers): (Registers, Chunk[Chunk[Byte]]) =
        val buf  = RechunkLayout.buf.get(h, RegisterOffset.Zero)
        val fill = RechunkLayout.fill.get(h, RegisterOffset.Zero)
        if fill > 0 then
          RechunkLayout.fill.set(h, RegisterOffset.Zero, 0)
          (h, Chunk.single(Chunk.fromArray(java.util.Arrays.copyOf(buf, fill))))
        else (h, Chunk.empty)
      def toSummary(h: Registers): Long =
        RechunkLayout.blockCount.get(h, RegisterOffset.Zero)

  /** Composed pipeline: count >>> hash >>> rechunk (register-backed). */
  def countHashRechunk(
    blockSize: Int,
    algo: HashAlgo = HashAlgo.runtimeDefault,
  ) =
    countBytes >>> hashBytes(algo) >>> rechunk(blockSize)

end RegisterIngestPipeline
