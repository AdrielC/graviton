package graviton.core.scan

import kyo.Record
import kyo.Record.`~`
import zio.Chunk

/**
 * Throughput monitoring transducer.
 *
 * Pass-through that tracks bytes processed and elapsed wall-clock time
 * (approximated via `System.nanoTime`). Compose with `&&&` to observe
 * any pipeline without affecting its output:
 *
 * {{{
 * val observed = ingestPipeline &&& ThroughputMonitor()
 * }}}
 *
 * Summary fields: `monitoredBytes`, `monitoredChunks`, `elapsedNanos`, `bytesPerSecond`.
 */
object ThroughputMonitor:

  type Summary = Record[
    ("monitoredBytes" ~ Long) & ("monitoredChunks" ~ Long) & ("elapsedNanos" ~ Long) & ("bytesPerSecond" ~ Double)
  ]

  def apply(): Transducer[Chunk[Byte], Chunk[Byte], Summary] =
    new Transducer[Chunk[Byte], Chunk[Byte], Summary]:
      type Hot = (Long, Long, Long) // bytes, chunks, startNanos

      def initHot: Hot = (0L, 0L, System.nanoTime())

      def step(h: Hot, chunk: Chunk[Byte]): (Hot, Chunk[Chunk[Byte]]) =
        ((h._1 + chunk.length.toLong, h._2 + 1L, h._3), Chunk.single(chunk))

      def flush(h: Hot): (Hot, Chunk[Chunk[Byte]]) = (h, Chunk.empty)

      def toSummary(h: Hot): Summary =
        val (bytes, chunks, startNanos) = h
        val elapsed                     = System.nanoTime() - startNanos
        val bps                         = if elapsed > 0 then bytes.toDouble / (elapsed.toDouble / 1e9) else 0.0
        (Record.empty & ("monitoredBytes" ~ bytes) & ("monitoredChunks" ~ chunks) & ("elapsedNanos" ~ elapsed) & (
          "bytesPerSecond" ~ bps
        )).asInstanceOf[Summary]

      override def stepChunk(h: Hot, chunks: Chunk[Chunk[Byte]]): (Hot, Chunk[Chunk[Byte]]) =
        var totalBytes = h._1
        var idx        = 0
        while idx < chunks.length do
          totalBytes += chunks(idx).length.toLong
          idx += 1
        ((totalBytes, h._2 + chunks.length.toLong, h._3), chunks)

end ThroughputMonitor
