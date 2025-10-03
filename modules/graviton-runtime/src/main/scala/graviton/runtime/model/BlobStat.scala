package graviton.runtime.model

import java.time.Instant

final case class BlobStat(size: Long, etag: String, lastModified: Instant)
