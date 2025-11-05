package graviton.backend.pg

import graviton.core.locator.BlobLocator
import graviton.core.ranges.{RangeSet, Span}
import graviton.core.ranges.given
import graviton.runtime.indexes.RangeTracker
import zio.ZIO

final class PgRangeTracker extends RangeTracker:
  override def current(locator: BlobLocator): ZIO[Any, Throwable, RangeSet[Long]]                 = ZIO.succeed(RangeSet.empty[Long])
  override def merge(locator: BlobLocator, span: Span[Long]): ZIO[Any, Throwable, RangeSet[Long]] =
    ZIO.succeed(RangeSet.single(span))
