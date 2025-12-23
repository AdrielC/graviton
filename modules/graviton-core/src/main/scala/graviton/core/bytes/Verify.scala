package graviton.core.bytes

object Verify:
  def matches(expected: HashAlgo, bytes: Hasher.Digestable): Boolean =
    Hasher
      .hasher(expected, None)
      .flatMap((hasher: Hasher) => hasher.update(bytes).digest)
      .exists((digest: Digest) => digest.length == expected.hexLength)
