package graviton.core.keys

import graviton.core.bytes.{Digest, HashAlgo}
import zio.schema.{DeriveSchema, Schema}

final case class KeyBits(algo: HashAlgo, digest: Digest, size: Long):
  /** Stable, round-trippable text form used by the CLI and HTTP API. */
  def render: String =
    s"${algo.primaryName}:${digest.hex.value}:$size"

object KeyBits:

  import scala.quoted.*
  import scala.quoted.Expr

  given FromExpr[KeyBits] = new FromExpr[KeyBits] {
    def unapply(value: Expr[KeyBits])(using Quotes): Option[KeyBits] =
      value match
        case '{ KeyBits(${ Expr(algo: HashAlgo) }, ${ Expr(digest: Digest) }, ${ Expr(size: Long) }) } =>
          Some(KeyBits(algo, digest, size))
        case _                                                                                         => None
  }

  given ToExpr[KeyBits] = new ToExpr[KeyBits] {
    def apply(value: KeyBits)(using Quotes): Expr[KeyBits] =
      '{
        KeyBits(
          ${ Expr(value.algo) },
          ${ Expr(value.digest) },
          ${ Expr(value.size) },
        )
      }
  }

  def create(algo: HashAlgo, digest: Digest, size: Long): Either[String, KeyBits] =
    if size < 0 then Left("Size must be non-negative")
    else if digest.length != algo.hashBytes then Left("Digest length mismatch")
    else Right(KeyBits(algo, digest, size))

  def fromString(value: String): Either[String, KeyBits] =
    value.split(":", -1).toList match
      case algoText :: digestText :: sizeText :: Nil =>
        for {
          algo    <- HashAlgo.fromString(algoText).toRight(s"Unsupported hash algorithm '$algoText'")
          digest  <- Digest.fromString(digestText)
          size    <- scala.util.Try(sizeText.toLong).toEither.left.map(_ => s"Invalid byte length '$sizeText'")
          keyBits <- KeyBits.create(algo, digest, size)
        } yield keyBits
      case _                                         =>
        Left("Expected a content key in the form <algorithm>:<hex-digest>:<byte-length>")

  inline given Schema[KeyBits] = DeriveSchema.gen[KeyBits]
