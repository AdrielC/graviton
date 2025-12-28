package graviton.server.shard

import graviton.core.ranges.Span
import graviton.core.types.BlobOffset
import zio.ZIO

trait MultipartCoordinator:
  def record(span: Span[BlobOffset]): ZIO[Any, Throwable, Unit]
