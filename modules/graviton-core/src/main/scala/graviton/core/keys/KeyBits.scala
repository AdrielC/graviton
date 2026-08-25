package graviton.core.keys

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.shared.cas.ContentKeyText
import zio.schema.{DeriveSchema, Schema}

final case class KeyBits(algo: HashAlgo, digest: Digest, size: Long):
  /** Stable, round-trippable text form used by the CLI and HTTP API. */
  def render: String =
    ContentKeyText.render(algo.primaryName, digest.hex.value, size)

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
    for
      parts   <- ContentKeyText.parse(value)
      algo    <- HashAlgo.fromString(parts.algorithm).toRight(s"Unsupported hash algorithm '${parts.algorithm}'")
      digest  <- Digest.fromString(parts.digestHex)
      keyBits <- KeyBits.create(algo, digest, parts.size)
    yield keyBits

  inline given Schema[KeyBits] = DeriveSchema.gen[KeyBits]
