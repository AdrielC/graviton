package graviton.core.bytes

import java.security.MessageDigest
import scala.annotation.targetName

object Verify:
  def matches[A: Hashable](expected: Hash, value: A): Boolean =
    Hash(expected.algo)(value).exists(actual => MessageDigest.isEqual(expected.bytes.toInteropArray, actual.bytes.toInteropArray))

  @targetName("matches")
  def matchesLegacy(expected: HashAlgo, value: Hasher.Digestable): Boolean =
    Hasher
      .hasher(expected, None)
      .flatMap((hasher: Hasher) => hasher.updateLegacy(value).hash)
      .exists((hash: Hash) => hash.bytes.length == expected.hashBytes)
