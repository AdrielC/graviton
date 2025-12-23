package graviton.core.macros

import graviton.core.keys.KeyBits
import graviton.core.locator.BlobLocator
import graviton.core.ranges.Span
import graviton.core.ranges.given
import graviton.core.types.HexLower
import graviton.core.bytes.Digest

import scala.quoted.*

object Interpolators:

  extension (inline sc: StringContext)
    inline def hex(inline args: Any*): HexLower =
      ${ hexHexLowerImpl('sc, 'args) }

    inline def bin(inline args: Any*): KeyBits =
      ${ binKeyBitsImpl('sc, 'args) }

    inline def locator(inline args: Any*): BlobLocator =
      ${ locatorImpl('sc, 'args) }

    inline def span(inline args: Any*): Span[Long] =
      ${ spanImpl('sc, 'args) }

  private def ensureNoArgs(name: String, argsExpr: Expr[Seq[Any]])(using Quotes): Unit =
    argsExpr match
      case Varargs(Seq()) => ()
      case _              => quotes.reflect.report.error(s"$name interpolator does not support arguments")

  private def hexHexLowerImpl(scExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using Quotes): Expr[HexLower] =
    import quotes.reflect.report
    ensureNoArgs("hex", argsExpr)

    scExpr.value match
      case Some(ctx) =>
        val literal = ctx.parts.mkString
        val trimmed = literal.trim
        val lower   = trimmed.toLowerCase
        val isHex   = lower.length >= 2 && lower.forall(ch => ch.isDigit || (ch >= 'a' && ch <= 'f'))
        if !isHex then
          report.error(s"hex literal must contain only [0-9a-f], received '$literal'")
          '{ compiletime.error("hex literal must contain only [0-9a-f]") }
        else Expr(lower).asInstanceOf[Expr[HexLower]]
      case None      =>
        report.error("hex interpolator must be invoked with a literal")
        '{ compiletime.error("hex interpolator must be invoked with a literal") }

  private def binKeyBitsImpl(scExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using Quotes): Expr[KeyBits] =
    import quotes.reflect.report
    ensureNoArgs("bin", argsExpr)

    scExpr.value match
      case Some(ctx) =>
        val literal = ctx.parts.mkString
        val trimmed = literal.trim
        val isBin   = trimmed.nonEmpty && trimmed.forall(ch => ch == '0' || ch == '1')

        if !isBin then
          report.error(s"bin literal must contain only [0-1], received '$literal'")
          '{ compiletime.error("bin literal must contain only [0-1]") }
        else
          Digest.digest(trimmed) match
            case Right(bits) => Expr(bits)
            case Left(err)   =>
              report.error(err)
              '{ compiletime.error(${ Expr(err) }) }

      case None =>
        report.error("bin interpolator must be invoked with a literal")
        '{ compiletime.error("bin interpolator must be invoked with a literal") }

  private def locatorImpl(scExpr: Expr[StringContext], argsExpr: Expr[Seq[Any]])(using Quotes): Expr[BlobLocator] =
    import quotes.reflect.report
    ensureNoArgs("locator", argsExpr)

    val literal =
      scExpr.value match
        case Some(ctx) => ctx.parts.mkString
        case None      =>
          report.error("locator interpolator must be invoked with a literal")
          ""

    locatorPattern.findFirstMatchIn(literal) match
      case Some(m) =>
        '{ BlobLocator(${ Expr(m.group(1)) }, ${ Expr(m.group(2)) }, ${ Expr(m.group(3)) }) }
      case None    =>
        report.error(s"locator literal must match '<scheme>://<bucket>/<path>', received '$literal'")
        '{ compiletime.error("locator literal must match '<scheme>://<bucket>/<path>'") }

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

  private val locatorPattern = """([a-zA-Z0-9+.-]+)://([^/]+)/(.+)""".r

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
