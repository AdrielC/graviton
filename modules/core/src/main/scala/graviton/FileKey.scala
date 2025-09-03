package graviton

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

final case class FileKey(
    hash: Hash,
    algo: HashAlgorithm,
    size: Long :| GreaterEqual[0],
    mediaType: String
)

final case class FileKeySelector(prefix: Option[Array[Byte]] = None)
