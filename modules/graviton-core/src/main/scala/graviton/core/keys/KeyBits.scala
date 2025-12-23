package graviton.core.keys

import graviton.core.bytes.{Digest, HashAlgo}
import zio.schema.{DeriveSchema, Schema}

final case class KeyBits(algo: HashAlgo, digest: Digest, size: Long)

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
      Expr(KeyBits(value.algo, value.digest, value.size))
  }

  def create(algo: HashAlgo, digest: Digest, size: Long): Either[String, KeyBits] =
    if size < 0 then Left("Size must be non-negative")
    else if digest.length != algo.hashBytes then Left("Digest length mismatch")
    else Right(KeyBits(algo, digest, size))

  def fromString(value: String): Either[String, KeyBits] =
    HashAlgo.keyBitsRegex.findFirstMatchIn(value) match
      case Some(m) =>
        for {
          algo    <- HashAlgo.fromString(m.group(1)).toRight("Invalid algo")
          digest  <- Digest.fromString(m.group(2))
          size    <- scala.util.Try(m.group(3).toLong).toEither.left.map(_ => "Invalid size")
          keyBits <- KeyBits.create(algo, digest, size)
        } yield keyBits
      case None    =>
        Digest.digest(value)

  inline given Schema[KeyBits] = DeriveSchema.gen[KeyBits]
