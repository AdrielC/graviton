package graviton.core.keys

import zio.schema.{DeriveSchema, Schema}
import zio.Chunk
import zio.stream.ZStream
import graviton.core.bytes.Digest
import graviton.core.bytes.Hasher

import scala.quoted.*

import scala.quoted.ToExpr

import java.io.File as JFile

extension (inline ctx: StringContext)
  inline def file(inline args: Any*): JFile =
    ${ fileImpl('ctx, 'args) }

private def fileImpl(ctx: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using quotes: Quotes): Expr[JFile] =
  ctx.value match
    case Some(_) =>
      argsExpr match
        case Varargs(Seq()) =>
          '{ compiletime.error("file interpolator requires a literal path") }

        case Varargs(Seq(arg: Expr[String] @unchecked)) =>
          '{ JFile(${ arg.asExprOf[String] }) }

        case _ =>
          quotes.reflect.report.error("file interpolator does not support runtime arguments")
          '{ compiletime.error("file interpolator requires a literal path") }

    case None =>
      quotes.reflect.report.error("string interpolator must be invoked with a literal")
      '{ compiletime.error("file interpolator requires a literal path") }

/**
 * Provides the `hex` string interpolator, which returns `ByteVector` instances from hexadecimal strings.
 *
 * @example {{{
 * scala> val b = hex"deadbeef"
 * val b: scodec.bits.ByteVector = ByteVector(4 bytes, 0xdeadbeef)
 * }}}
 */
extension (inline ctx: StringContext)

  inline def hex(inline args: Any*): Digest =
    ${ hexImpl('ctx, 'args) }

/**
 * Provides the `bin` string interpolator, which returns `BitVector` instances from binary strings.
 *
 * @example {{{
 * scala> val b = bin"1010101010"
 * val b: scodec.bits.BitVector = BitVector(10 bits, 0xaa8)
 * }}}
 */
extension (inline ctx: StringContext) inline def bin(inline args: Any*): Either[Throwable, Chunk[Byte]] = ${ binImpl('ctx, 'args) }

private def extractLiteralParts(sc: Expr[StringContext])(using Quotes): List[String] =
  sc.value match
    case Some(ctx) => ctx.parts.toList
    case None      =>
      quotes.reflect.report.error("string interpolator must be invoked with a literal")
      Nil
end extractLiteralParts

private def hexImpl(ctx: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using quotes: Quotes): Expr[Digest] =
  ctx.value match
    case Some(_) =>

      argsExpr match
        case Varargs(Seq()) =>
          '{ Digest(zio.Chunk.fromArray(${ Expr(extractLiteralParts(ctx)) }.toArray.flatMap(_.getBytes))) }

        case Varargs(Seq(arg: Expr[JFile] @unchecked)) =>
          '{
            val file   = ${ arg.asExprOf[JFile] }
            val digest = zio.Unsafe.unsafe { implicit unsafe =>
              zio.Runtime.default.unsafe
                .run(ZStream.fromFile(file).run(Hasher.sink(None)))
                .getOrThrowFiberFailure()
            }
            Digest(digest.value)
          }

        case _ =>
          quotes.reflect.report.error("hex interpolator does not support runtime arguments")
          '{ compiletime.error("hex interpolator does not support runtime arguments") }

    case _ =>
      quotes.reflect.report.error("string interpolator must be invoked with a literal")
      '{ compiletime.error("string interpolator must be invoked with a literal") }

private def binImpl(ctxExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using Quotes): Expr[Either[Throwable, Chunk[Byte]]] =

  def extractLiteralParts(scExpr: Expr[StringContext])(using Quotes): List[String] =
    scExpr.value match
      case Some(ctx) => ctx.parts.toList
      case None      =>
        quotes.reflect.report.error("string interpolator must be invoked with a literal")
        Nil

  def ensureNoArgs(name: String, argsExpr: Expr[Seq[Any]])(using Quotes): Unit =
    argsExpr match
      case Varargs(Seq()) => ()
      case _              => quotes.reflect.report.error(s"$name interpolator does not support arguments")

  val parts   = extractLiteralParts(ctxExpr: Expr[StringContext])
  ensureNoArgs("bin", argsExpr)
  val literal = parts.mkString
  val isBin   = literal.nonEmpty && literal.forall(ch => ch == '0' || ch == '1')
  if !isBin then
    quotes.reflect.report.error(s"bin literal must contain only [0-1], received '$literal'")
    '{ Left(new Throwable("Failed to parse bin")) }
  else
    '{
      Right(${
        Expr(Chunk.fromArray(literal.getBytes))(
          using new ToExpr[zio.Chunk[Byte]] {
            def apply(value: zio.Chunk[Byte])(using Quotes): Expr[zio.Chunk[Byte]] =
              '{ zio.Chunk.fromArray(${ Expr(value.toArray) }.toArray) }
          }
        )
      })
    }

sealed trait BinaryKey derives CanEqual:
  def bits: KeyBits

object BinaryKey:

  final case class Blob(bits: KeyBits)     extends BinaryKey
  final case class Block(bits: KeyBits)    extends BinaryKey
  final case class Chunk(bits: KeyBits)    extends BinaryKey
  final case class Manifest(bits: KeyBits) extends BinaryKey

  final case class View(key: BinaryKey, transform: ViewTransform) extends BinaryKey {
    override def bits: KeyBits = key.bits
  }

  object View:
    def apply(key: KeyBits, transform: ViewTransform): View =
      View(Manifest(key), transform)

  def blob(bits: KeyBits): Either[String, Blob] =
    if bits.size < 0 then Left("Blob size cannot be negative") else Right(Blob(bits))

  def block(bits: KeyBits): Either[String, Block] =
    if bits.size <= 0 then Left("Block size must be positive") else Right(Block(bits))

  def chunk(bits: KeyBits): Either[String, Chunk] =
    if bits.size < 0 then Left("Chunk size cannot be negative") else Right(Chunk(bits))

  def manifest(bits: KeyBits): Either[String, Manifest] =
    if bits.size < 0 then Left("Manifest size cannot be negative") else Right(Manifest(bits))

  def view(key: BinaryKey, transform: ViewTransform): View =
    View(key, transform)

  given Schema[BinaryKey] = DeriveSchema.gen[BinaryKey]
