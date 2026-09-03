package graviton.core.bytes

import graviton.core.RefinedSubtypeExt
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.Chunk

/**
 * Bytes admitted as the output of an executable Graviton [[HashAlgo]].
 *
 * This is a real subtype of [[Digest]]. Legacy or external digest metadata can
 * remain a `Digest` without being accepted as a supported hash result.
 */
type HashBytes = HashBytes.T

object HashBytes extends RefinedSubtypeExt[Digest, MinLength[20] & MaxLength[32]]:

  def fromDigest(value: Digest): Either[HashError, HashBytes] =
    either(value).left
      .map(_ => HashError.InvalidByteLength(value.length, minimum = 20, maximum = 32))

  def fromChunk(value: Chunk[Byte]): Either[HashError, HashBytes] =
    Digest
      .fromChunk(value)
      .left
      .map(_ => HashError.InvalidByteLength(value.length, minimum = 20, maximum = 32))
      .flatMap(fromDigest)

  def fromString(value: String): Either[HashError, HashBytes] =
    Digest
      .fromString(value)
      .left
      .map(_ => HashError.InvalidHex(value))
      .flatMap(fromDigest)
