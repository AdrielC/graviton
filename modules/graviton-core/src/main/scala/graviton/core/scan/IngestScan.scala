package graviton.core.scan

import graviton.core.bytes.*
import kyo.Record
import kyo.Record.`~`
import zio.ChunkBuilder
import zio.Chunk

/**
 * Useful, production-oriented scans for ingest-style pipelines:
 * chunking (FastCDC + optional anchor), per-block hashing, whole-stream hashing,
 * and counters (bytes + blocks) in a single `FreeScan`.
 */
object IngestScan:

  type Event = Record[
    ("kind" ~ String) &
      ("blockIndex" ~ Long) &
      ("offset" ~ Long) &
      ("size" ~ Int) &
      ("reason" ~ Option[String]) &
      ("totalBytes" ~ Long) &
      ("totalBlocks" ~ Long) &
      ("blockBytes" ~ Option[Chunk[Byte]]) &
      ("blockDigest" ~ Option[Either[String, Chunk[Byte]]]) &
      ("streamDigest" ~ Option[Either[String, Chunk[Byte]]])
  ]

  private def event(
    kind: String,
    blockIndex: Long,
    offset: Long,
    size: Int,
    reason: Option[String],
    totalBytes: Long,
    totalBlocks: Long,
    blockBytes: Option[Chunk[Byte]],
    blockDigest: Option[Either[String, Chunk[Byte]]],
    streamDigest: Option[Either[String, Chunk[Byte]]],
  ): Event =
    (Record.empty
      & ("kind" ~ kind)
      & ("blockIndex" ~ blockIndex)
      & ("offset" ~ offset)
      & ("size" ~ size)
      & ("reason" ~ reason)
      & ("totalBytes" ~ totalBytes)
      & ("totalBlocks" ~ totalBlocks)
      & ("blockBytes" ~ blockBytes)
      & ("blockDigest" ~ blockDigest)
      & ("streamDigest" ~ streamDigest)).asInstanceOf[Event]

  inline private def log2(n: Int): Int =
    if n <= 0 then 0 else 32 - Integer.numberOfLeadingZeros(n - 1)

  private def computeBlockDigest(algo: HashAlgo, bytes: Chunk[Byte]): Either[String, Chunk[Byte]] =
    Hasher
      .hasher(algo, None)
      .map(_.update(bytes))
      .flatMap(_.digest)
      .map(_.value)

  /**
   * FastCDC ingest scan.
   *
   * - **Input**: `Chunk[Byte]` (upstream can use `.chunks`)
   * - **Outputs**:
   *   - one `"block"` event per boundary with `block`, `blockDigest`, counters, and boundary `reason`
   *   - a final `"final"` event with `streamDigest` and final totals
   *
   * Boundary reasons:
   * - `"anchor"`: optional anchor sequence matched (if provided)
   * - `"roll"`: rolling hash matched (content-defined boundary)
   * - `"max"`: hard maximum chunk size reached
   * - `"eof"`: end-of-stream flush
   */
  def fastCdc(
    algo: HashAlgo = HashAlgo.runtimeDefault,
    minSize: Int = 256,
    avgSize: Int = 1024,
    maxSize: Int = 4096,
    anchor: Option[Chunk[Byte]] = None,
  ): FreeScan[Prim, Chunk[Byte], Event] =
    val safeMin = math.max(1, minSize)
    val safeMax = math.max(safeMin, math.min(maxSize, graviton.core.model.Block.maxBytes))
    val safeAvg = math.max(safeMin, math.min(avgSize, safeMax))

    // Same mask heuristic as the frontend implementation.
    val avgPower = math.max(1, log2(safeAvg))
    val mask     = (1 << (math.max(1, avgPower) - 1)) - 1

    val anchorBytes: Array[Byte] = anchor.map(_.toArray).getOrElse(Array.emptyByteArray)
    val anchorLen                = anchorBytes.length

    type S = Record[
      ("buf" ~ Array[Byte]) &
        ("len" ~ Int) &
        ("roll" ~ Long) &
        ("anchorIdx" ~ Int) &
        ("totalBytes" ~ Long) &
        ("totalBlocks" ~ Long) &
        ("blockIndex" ~ Long) &
        ("blockStart" ~ Long) &
        ("streamHasher" ~ Either[String, Hasher])
    ]

    val init: S =
      (Record.empty
        & ("buf" ~ Array.ofDim[Byte](safeMax))
        & ("len" ~ 0)
        & ("roll" ~ 0L)
        & ("anchorIdx" ~ 0)
        & ("totalBytes" ~ 0L)
        & ("totalBlocks" ~ 0L)
        & ("blockIndex" ~ 0L)
        & ("blockStart" ~ 0L)
        & ("streamHasher" ~ Hasher.hasher(algo, None))).asInstanceOf[S]

    FS.fold[Chunk[Byte], Event, S](init) { (state0, in) =>
      val out = ChunkBuilder.make[Event]()

      // Whole-stream hasher update once per input chunk.
      state0.streamHasher.foreach(_.update(in))

      var state    = state0
      val buf      = state0.buf
      var len      = state0.len
      var roll     = state0.roll
      var anchorIx = state0.anchorIdx

      var totalBytes  = state0.totalBytes
      var totalBlocks = state0.totalBlocks
      var blockIndex  = state0.blockIndex
      var blockStart  = state0.blockStart

      var idx = 0
      while idx < in.length do
        val b = in(idx)
        buf(len) = b
        len += 1
        totalBytes += 1

        val unsigned = b & 0xff
        roll = ((roll << 1) ^ unsigned.toLong) & 0xffffffL

        // Optional anchor matching (simple streaming prefix matcher).
        var anchorMatched = false
        if anchorLen > 0 then
          if b == anchorBytes(anchorIx) then
            anchorIx += 1
            if anchorIx == anchorLen then
              anchorMatched = true
              anchorIx = 0
          else anchorIx = if b == anchorBytes(0) then 1 else 0

        val currentSize = len

        val boundaryReason: Option[String] =
          if anchorMatched && currentSize >= safeMin then Some("anchor")
          else if currentSize >= safeMax then Some("max")
          else if currentSize >= safeMin && (mask == 0 || (roll & mask.toLong) == 0L) then Some("roll")
          else None

        boundaryReason match
          case Some(reason) =>
            val blockBytes = Chunk.fromArray(java.util.Arrays.copyOf(buf, len))
            val digest     = computeBlockDigest(algo, blockBytes)
            val evt        =
              event(
                kind = "block",
                blockIndex = blockIndex,
                offset = blockStart,
                size = len,
                reason = Some(reason),
                totalBytes = totalBytes,
                totalBlocks = totalBlocks + 1,
                blockBytes = Some(blockBytes),
                blockDigest = Some(digest),
                streamDigest = None,
              )
            out += evt

            totalBlocks += 1
            blockIndex += 1
            blockStart = totalBytes
            len = 0
            roll = 0L

          case None => ()

        idx += 1

      state = (Record.empty
        & ("buf" ~ buf)
        & ("len" ~ len)
        & ("roll" ~ roll)
        & ("anchorIdx" ~ anchorIx)
        & ("totalBytes" ~ totalBytes)
        & ("totalBlocks" ~ totalBlocks)
        & ("blockIndex" ~ blockIndex)
        & ("blockStart" ~ blockStart)
        & ("streamHasher" ~ state0.streamHasher)).asInstanceOf[S]

      (state, out.result())
    } { state =>
      val out = ChunkBuilder.make[Event]()

      // Flush trailing bytes as a final block, if any.
      if state.len > 0 then
        val bytes     = Chunk.fromArray(java.util.Arrays.copyOf(state.buf, state.len))
        val digest    = computeBlockDigest(algo, bytes)
        val nextBytes = state.totalBytes + state.len
        val nextBlk   = state.totalBlocks + 1
        out += event(
          kind = "block",
          blockIndex = state.blockIndex,
          offset = state.blockStart,
          size = state.len,
          reason = Some("eof"),
          totalBytes = nextBytes,
          totalBlocks = nextBlk,
          blockBytes = Some(bytes),
          blockDigest = Some(digest),
          streamDigest = None,
        )

      val streamDigest = state.streamHasher.flatMap(_.digest).map(_.value)
      out += event(
        kind = "final",
        blockIndex = state.blockIndex,
        offset = state.totalBytes,
        size = 0,
        reason = None,
        totalBytes = state.totalBytes,
        totalBlocks = state.totalBlocks,
        blockBytes = None,
        blockDigest = None,
        streamDigest = Some(streamDigest),
      )

      out.result()
    }
