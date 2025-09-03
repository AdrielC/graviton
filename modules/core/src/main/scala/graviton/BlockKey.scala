package graviton

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

final case class BlockKey(hash: Hash, size: Int :| Positive)

final case class BlockKeySelector(prefix: Option[Array[Byte]] = None)
