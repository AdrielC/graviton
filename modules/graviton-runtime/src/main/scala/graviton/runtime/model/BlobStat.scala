package graviton.runtime.model

import java.time.Instant
import zio.schema.DeriveSchema

final case class BlobStat(size: Long, etag: String, lastModified: Instant)

object BlobStat:
  given zio.schema.Schema[BlobStat] = DeriveSchema.gen[BlobStat]
