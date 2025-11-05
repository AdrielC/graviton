package graviton.protocol.http

import zio.schema.{DeriveSchema, Schema}

final case class UploadResponse(key: String)

object JsonCodecs:
  given Schema[UploadResponse] = DeriveSchema.gen[UploadResponse]
