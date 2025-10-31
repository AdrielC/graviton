package graviton.server.shard

import graviton.core.ranges.Span
import graviton.runtime.indexes.RangeTracker
import zio.ZIO
import scala.annotation.unused

final case class MultipartCoordinator(rangeTracker: RangeTracker):
  def record(@unused span: Span[Long]): ZIO[Any, Throwable, Unit] = ZIO.unit
