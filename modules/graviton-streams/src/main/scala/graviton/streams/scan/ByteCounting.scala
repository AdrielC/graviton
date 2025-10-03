package graviton.streams.scan

import zio.Chunk
import zio.stream.{ZPipeline, ZStream}

object ByteCounting:

  /** Running total of the bytes observed so far. */
  val runningTotal: Scan[Long, Long, Long] =
    Scan.stateful[Long, Long, Long](0L, Chunk.single(0L)) { (state, next) =>
      val updated = state + next
      (updated, Chunk.single(updated))
    }

  /** Convenience alias for working with byte-sized integers. */
  val runningTotalInt: Scan[Int, Long, Long] =
    runningTotal.contramap(_.toLong)

  def pipeline: ZPipeline[Any, Nothing, Long, Long] = runningTotal.pipeline

  def accumulate[R, E](stream: ZStream[R, E, Long]): ZStream[R, E, Long] =
    stream.via(pipeline)
