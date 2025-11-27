package graviton.core.macros

import graviton.core.locator.BlobLocator
import graviton.core.ranges.Span
import graviton.core.ranges.given
import graviton.core.types.*
import scala.quoted.*

object Interpolators:

  extension (inline sc: StringContext)
    inline def hex(inline args: Any*): HexLower =
      ${ hexImpl('sc, 'args) }

    inline def locator(inline args: Any*): BlobLocator =
      ${ locatorImpl('sc, 'args) }

    inline def span(inline args: Any*): Span[Long] =
      ${ spanImpl('sc, 'args) }

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

  private def spanImpl(scExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using Quotes): Expr[Span[Long]] =
    argsExpr match
      case Varargs(Seq()) =>
        scExpr.value match
          case Some(ctx) =>
            SpanHelper.parseEither(ctx.parts.mkString) match
              case Right(span) =>
                '{ Span.unsafe[Long](${ Expr(span.startInclusive) }, ${ Expr(span.endInclusive) }) }
              case Left(err)   =>
                quotes.reflect.report.error(err)
                '{ Span.unsafe[Long](0L, -1L) }
          case None      =>
            runtimeSpan(scExpr, argsExpr)
      case _              =>
        runtimeSpan(scExpr, argsExpr)

  private def runtimeSpan(scExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using Quotes): Expr[Span[Long]] =
    '{ Interpolators.SpanHelper.parse(${ scExpr }.s(${ argsExpr }*)) }

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

  private[macros] object SpanHelper:
    def parse(input: String): Span[Long] =
      parseEither(input) match
        case Right(span) => span
        case Left(err)   => throw IllegalArgumentException(err)

    def parseEither(input: String): Either[String, Span[Long]] =
      val trimmed = input.trim
      if trimmed.isEmpty then Left("Span literal cannot be empty")
      else
        val (startInclusive, afterStart) =
          trimmed.head match
            case '[' => (true, trimmed.tail)
            case '(' => (false, trimmed.tail)
            case _   => (true, trimmed)

        val (core, endInclusive) =
          afterStart.lastOption match
            case Some(']') => (afterStart.init, true)
            case Some(')') => (afterStart.init, false)
            case _         => (afterStart, true)

        val parts = core.split("\\.\\.", 2)
        if parts.length != 2 then Left(s"Span literal '$input' must use '..' to separate bounds")
        else
          val startPart = parts(0).trim
          val endPart   = parts(1).trim

          for
            start <- parseLong(startPart)
            end   <- parseLong(endPart)
            span  <- Span.fromBounds(start, end, startInclusive, endInclusive)
          yield span

    private def parseLong(value: String): Either[String, Long] =
      try Right(java.lang.Long.parseLong(value))
      catch case _: NumberFormatException => Left(s"Span bound '$value' is not a valid integer")
