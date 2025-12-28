package graviton.runtime.indexes

import graviton.core.locator.BlobLocator
import graviton.core.ranges.{RangeSet, Span}
import graviton.core.types.Offset
import zio.ZIO

trait RangeTracker:
  def current(locator: BlobLocator): ZIO[Any, Throwable, RangeSet[Offset]]
  def merge(locator: BlobLocator, span: Span[Offset]): ZIO[Any, Throwable, RangeSet[Offset]]
