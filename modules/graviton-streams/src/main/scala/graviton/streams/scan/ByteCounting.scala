package graviton.streams.scan

import zio.stream.ZStream

object ByteCounting:
  def accumulate(stream: ZStream[Any, Nothing, Int]): ZStream[Any, Nothing, Long] =
    stream.mapAccum(0L)((total, next) =>
      val updated = total + next
      (updated, updated)
    )
