package torrent

import io.github.iltotore.iron.constraint.all.*
import torrent.schemas.RefinedTypeExt

import zio.schema.*

/**
 * Content-addressable reference to a raw blob (chunk or whole file) Always
 * immutable and based on content hash
 */
final case class BlobKey(algo: HashAlgo, digest: DigestHex):
  def mkString: String          = s"${algo.canonicalName}:$digest"
  override def toString: String = mkString

object BlobKey:
  given Schema[BlobKey] = DeriveSchema.gen[BlobKey]

  def fromString(str: String): Either[String, BlobKey] =
    str.split(":", 2) match
      case Array(algoStr, digestStr) =>
        for
          algo   <- HashAlgo.parse(algoStr)
          digest <- DigestHex.either(digestStr)
        yield BlobKey(algo, digest)
      case _                         => Left(s"Invalid BlobKey format: $str")

  /**
   * Hex-encoded digest string
   */
  type DigestHex = DigestHex.T
  object DigestHex extends RefinedTypeExt[String, Match["""^[a-fA-F0-9]+$"""] & MinLength[8]]:
    def fromString(str: String): Either[String, DigestHex] =
      either(str)
    def fromStringUnsafe(str: String): DigestHex           =
      applyUnsafe(str)

end BlobKey

export BlobKey.DigestHex
