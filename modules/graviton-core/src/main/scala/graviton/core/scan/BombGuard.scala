package graviton.core.scan

import kyo.Record
import kyo.Record.`~`
import zio.Chunk

/**
 * Ingestion bomb protection transducer.
 *
 * Monitors total bytes flowing through the pipeline and stops emitting
 * once a configurable limit is exceeded. The summary records how many
 * bytes were seen and whether the upload was rejected.
 *
 * Compose at the front of any ingest pipeline:
 * {{{
 * val safePipeline = BombGuard(maxBytes = 10L * 1024 * 1024 * 1024) >>> ingestPipeline
 * }}}
 *
 * When the limit is exceeded, subsequent chunks are silently dropped.
 * The `rejected` flag in the summary indicates whether truncation occurred.
 */
object BombGuard:

  type Summary = Record[("totalSeen" ~ Long) & ("rejected" ~ Boolean) & ("maxBytes" ~ Long)]

  /**
   * Create a bomb guard transducer with the given byte limit.
   *
   * @param maxBytes Maximum number of bytes to allow through.
   *                 Chunks that would push the total past this limit
   *                 are dropped entirely (no partial emission).
   */
  def apply(
    maxBytes: Long
  ): Transducer[Chunk[Byte], Chunk[Byte], Summary] =
    val limit = math.max(1L, maxBytes)
    new Transducer[Chunk[Byte], Chunk[Byte], Summary]:
      type Hot = (Long, Boolean) // totalSeen, rejected

      def initHot: Hot = (0L, false)

      def step(h: Hot, chunk: Chunk[Byte]): (Hot, Chunk[Chunk[Byte]]) =
        val (seen, wasRejected) = h
        if wasRejected then
          // Already past the limit — drop silently
          ((seen + chunk.length.toLong, true), Chunk.empty)
        else
          val nextSeen = seen + chunk.length.toLong
          if nextSeen > limit then
            // This chunk would exceed the limit — reject
            ((nextSeen, true), Chunk.empty)
          else
            // Under the limit — pass through
            ((nextSeen, false), Chunk.single(chunk))

      def flush(h: Hot): (Hot, Chunk[Chunk[Byte]]) = (h, Chunk.empty)

      def toSummary(h: Hot): Summary =
        val (seen, rejected) = h
        (Record.empty & ("totalSeen" ~ seen) & ("rejected" ~ rejected) & ("maxBytes" ~ limit)).asInstanceOf[Summary]

end BombGuard
