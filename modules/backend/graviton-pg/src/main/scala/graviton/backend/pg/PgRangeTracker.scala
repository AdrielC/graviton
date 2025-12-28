package graviton.backend.pg

import graviton.core.locator.BlobLocator
import graviton.core.ranges.{RangeSet, Span}
import graviton.core.ranges.given
import graviton.core.types.BlobOffset
import graviton.runtime.indexes.RangeTracker
import zio.ZIO

final class PgRangeTracker extends RangeTracker:
  override def current(locator: BlobLocator): ZIO[Any, Throwable, RangeSet[BlobOffset]]                       =
    ZIO.succeed(RangeSet.empty[BlobOffset])
  override def merge(locator: BlobLocator, span: Span[BlobOffset]): ZIO[Any, Throwable, RangeSet[BlobOffset]] =
    ZIO.succeed(RangeSet.single(span))
