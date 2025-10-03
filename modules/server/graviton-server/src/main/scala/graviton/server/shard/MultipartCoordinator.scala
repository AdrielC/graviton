package graviton.server.shard

import graviton.core.ranges.Span
import graviton.runtime.indexes.RangeTracker
import zio.ZIO

final case class MultipartCoordinator(rangeTracker: RangeTracker):
  def record(span: Span[Long]): ZIO[Any, Throwable, Unit] = ZIO.unit
