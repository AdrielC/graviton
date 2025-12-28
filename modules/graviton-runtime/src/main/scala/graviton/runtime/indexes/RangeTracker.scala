package graviton.runtime.indexes

import graviton.core.locator.BlobLocator
import graviton.core.ranges.{RangeSet, Span}
import graviton.core.types.BlobOffset
import zio.ZIO

trait RangeTracker:
  def current(locator: BlobLocator): ZIO[Any, Throwable, RangeSet[BlobOffset]]
  def merge(locator: BlobLocator, span: Span[BlobOffset]): ZIO[Any, Throwable, RangeSet[BlobOffset]]
