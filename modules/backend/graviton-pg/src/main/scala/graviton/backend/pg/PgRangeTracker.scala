package graviton.backend.pg

import graviton.core.locator.BlobLocator
import graviton.core.ranges.{RangeSet, Span}
import graviton.core.ranges.given
import graviton.core.types.Offset
import graviton.runtime.indexes.RangeTracker
import zio.ZIO

final class PgRangeTracker extends RangeTracker:
  override def current(locator: BlobLocator): ZIO[Any, Throwable, RangeSet[Offset]]                   = ZIO.succeed(RangeSet.empty[Offset])
  override def merge(locator: BlobLocator, span: Span[Offset]): ZIO[Any, Throwable, RangeSet[Offset]] =
    ZIO.succeed(RangeSet.single(span))
