package graviton.core.bytes

object Verify:
  def matches(expected: Digest, bytes: Array[Byte]): Boolean =
    val hasher = Hasher.memory(expected.algo)
    val _      = hasher.update(bytes)
    hasher.result.exists(_.value == expected.value)
