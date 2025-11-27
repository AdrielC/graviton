package graviton.runtime.model

import graviton.core.model.FileSize
import graviton.core.types.given
import java.time.Instant
import zio.schema.DeriveSchema

final case class BlobStat(size: FileSize, etag: String, lastModified: Instant)

object BlobStat:
  given zio.schema.Schema[BlobStat] = DeriveSchema.gen[BlobStat]
