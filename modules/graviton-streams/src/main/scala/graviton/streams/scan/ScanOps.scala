package graviton.streams.scan

import zio.stream.ZStream

object ScanOps:
  def scanOffsets(stream: ZStream[Any, Nothing, Int]): ZStream[Any, Nothing, (Long, Int)] =
    stream.zipWithIndex.map { case (value, idx) => (idx, value) }
