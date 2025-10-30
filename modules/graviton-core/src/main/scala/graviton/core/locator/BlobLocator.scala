package graviton.core.locator

import zio.schema.{DeriveSchema, Schema}

final case class BlobLocator(scheme: String, bucket: String, path: String)

object BlobLocator:
  given Schema[BlobLocator] = DeriveSchema.gen[BlobLocator]
