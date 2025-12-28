package graviton.server.shard

import graviton.core.ranges.Span
import graviton.core.types.Offset
import zio.ZIO

trait MultipartCoordinator:
  def record(span: Span[Offset]): ZIO[Any, Throwable, Unit]
