package torrent
package chunking

import zio.stream.*

final case class Chunker private (
  pipeline: ZPipeline[Any, Throwable, Byte, Bytes]
)
