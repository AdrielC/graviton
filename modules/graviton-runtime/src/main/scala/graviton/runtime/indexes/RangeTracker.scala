package graviton.runtime.indexes

import graviton.core.locator.BlobLocator
import graviton.core.ranges.{RangeSet, Span}
import graviton.core.types.BlobOffset
import graviton.runtime.stores.StoreError
import zio.IO

trait RangeTracker:
  def current(locator: BlobLocator): IO[StoreError, RangeSet[BlobOffset]]
  def merge(locator: BlobLocator, span: Span[BlobOffset]): IO[StoreError, RangeSet[BlobOffset]]
