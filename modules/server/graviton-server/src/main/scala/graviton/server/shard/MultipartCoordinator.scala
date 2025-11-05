package graviton.server.shard

import graviton.core.ranges.Span
import zio.ZIO

trait MultipartCoordinator:
  def record(span: Span[Long]): ZIO[Any, Throwable, Unit]
