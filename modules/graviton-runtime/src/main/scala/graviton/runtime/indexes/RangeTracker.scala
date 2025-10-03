package graviton.runtime.indexes

import graviton.core.locator.BlobLocator
import graviton.core.ranges.{RangeSet, Span}
import zio.ZIO

trait RangeTracker:
  def current(locator: BlobLocator): ZIO[Any, Throwable, RangeSet[Long]]
  def merge(locator: BlobLocator, span: Span[Long]): ZIO[Any, Throwable, RangeSet[Long]]
