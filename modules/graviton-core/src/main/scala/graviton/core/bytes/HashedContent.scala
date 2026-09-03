package graviton.core.bytes

import graviton.core.types.ContentLength

/**
 * A hash and the byte count observed by the same [[Hasher]].
 *
 * The constructor is confined to the hashing package so callers cannot pair a
 * digest with an unrelated declared length. Persisted content keys remain
 * claims until their corresponding bytes are streamed and verified.
 */
final case class HashedContent private[bytes] (hash: Hash, size: ContentLength) derives CanEqual

object HashedContent:

  private[bytes] def fromObserved(hash: Hash, size: Long): Either[HashError, HashedContent] =
    ContentLength
      .either(size)
      .left
      .map(message => HashError.InvariantViolation(s"Hashed content length is invalid: $message"))
      .map(HashedContent(hash, _))
