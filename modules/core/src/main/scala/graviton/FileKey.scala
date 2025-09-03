package graviton

import zio.schema.{DeriveSchema, Schema}

final case class FileKey(
    hash: Hash,
    algo: HashAlgorithm,
    size: Long,
    mediaType: String
)

final case class FileKeySelector(prefix: Option[Array[Byte]] = None)

object FileKey:
  given Schema[FileKey] = DeriveSchema.gen[FileKey]
