package graviton.core.macros

import graviton.core.locator.BlobLocator
import graviton.core.types.*
import scala.quoted.*

object Interpolators:

  extension (inline sc: StringContext)
    inline def hex(inline args: Any*): HexLower =
      ${ hexImpl('sc, 'args) }

    inline def locator(inline args: Any*): BlobLocator =
      ${ locatorImpl('sc, 'args) }

  private def hexImpl(scExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using Quotes): Expr[HexLower] =
    import quotes.reflect.report
    val parts   = extractLiteralParts(scExpr)
    ensureNoArgs("hex", argsExpr)
    val literal = parts.mkString
    val lower   = literal.toLowerCase
    val isHex   = lower.nonEmpty && lower.forall(ch => ch.isDigit || (ch >= 'a' && ch <= 'f'))
    if !isHex then
      report.error(s"hex literal must contain only [0-9a-f], received '$literal'")
      '{ "".asInstanceOf[HexLower] }
    else Expr(lower).asInstanceOf[Expr[HexLower]]

  private def locatorImpl(scExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using Quotes): Expr[BlobLocator] =
    import quotes.reflect.report
    val parts   = extractLiteralParts(scExpr)
    ensureNoArgs("locator", argsExpr)
    val literal = parts.mkString
    literal match
      case locatorPattern(scheme, bucket, path) =>
        '{ BlobLocator(${ Expr(scheme) }, ${ Expr(bucket) }, ${ Expr(path) }) }
      case _                                    =>
        report.error("locator literal must match '<scheme>://<bucket>/<path>' with lowercase scheme")
        '{ BlobLocator("", "", "") }

  private val locatorPattern = """([a-z0-9+.-]+)://([^/]+)/(.+)""".r

  private def extractLiteralParts(scExpr: Expr[StringContext])(using Quotes): List[String] =
    scExpr.value match
      case Some(ctx) => ctx.parts.toList
      case None      =>
        quotes.reflect.report.error("string interpolator must be invoked with a literal")
        Nil

  private def ensureNoArgs(name: String, argsExpr: Expr[Seq[Any]])(using Quotes): Unit =
    argsExpr match
      case Varargs(Seq()) => ()
      case _              => quotes.reflect.report.error(s"$name interpolator does not support arguments")
