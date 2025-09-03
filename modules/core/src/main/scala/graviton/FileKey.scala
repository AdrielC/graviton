package graviton

final case class FileKey(
    hash: Hash,
    algo: HashAlgorithm,
    size: Long,
    mediaType: String
)

final case class FileKeySelector(prefix: Option[Array[Byte]] = None)
