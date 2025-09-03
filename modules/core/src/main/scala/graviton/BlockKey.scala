package graviton

final case class BlockKey(hash: Hash, size: Int)

final case class BlockKeySelector(prefix: Option[Array[Byte]] = None)
