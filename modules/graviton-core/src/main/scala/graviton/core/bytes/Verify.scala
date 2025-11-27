package graviton.core.bytes

object Verify:
  def matches(expected: Digest, bytes: Array[Byte]): Boolean =
    Hasher
      .messageDigest(expected.algo)
      .flatMap { hasher =>
        val _ = hasher.update(bytes)
        hasher.result
      }
      .exists(_.value == expected.value)
