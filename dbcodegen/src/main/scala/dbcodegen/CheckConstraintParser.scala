package dbcodegen

import java.util.Locale
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.util.Try

object CheckConstraintParser:

  enum ComparisonOperator:
    case GreaterThan, GreaterThanOrEqual, LessThan, LessThanOrEqual, Equal, NotEqual

  enum LiteralValue:
    case Numeric(value: BigDecimal)
    case StringLiteral(value: String)
    case BooleanLiteral(value: Boolean)
    case Raw(value: String)

    def asString: String = this match
      case Numeric(value)       => value.toString
      case StringLiteral(value) => value
      case BooleanLiteral(value) => value.toString
      case Raw(value)           => value

  enum ValidationPlan:
    case NumericComparison(column: String, operator: ComparisonOperator, value: LiteralValue)
    case Between(column: String, lower: LiteralValue, upper: LiteralValue, lowerInclusive: Boolean, upperInclusive: Boolean)
    case LengthComparison(column: String, operator: ComparisonOperator, value: Int)
    case Inclusion(column: String, values: Seq[LiteralValue], negated: Boolean)
    case NotNull(column: String, negated: Boolean)

  final case class Parsed(plan: ValidationPlan, normalizedExpression: String)

  def parse(definition: String, targetColumn: String): Either[String, Parsed] =
    val normalized = normalize(definition)
    if normalized.isEmpty then Left("Empty check definition")
    else
      val columnKey = targetColumn.toLowerCase(Locale.ROOT)
      val result =
        splitTopLevel(normalized, "AND") match
          case Some((left, right)) =>
            (parseSingle(left, columnKey, targetColumn), parseSingle(right, columnKey, targetColumn)) match
              case (Right(Parsed(ValidationPlan.NumericComparison(_, op1, value1), _)),
                    Right(Parsed(ValidationPlan.NumericComparison(_, op2, value2), _))) =>
                arrangeBounds(op1, value1, op2, value2) match
                  case Some((lowerValue, lowerInclusive, upperValue, upperInclusive)) =>
                    Right(Parsed(
                      ValidationPlan.Between(targetColumn, lowerValue, upperValue, lowerInclusive, upperInclusive),
                      normalized,
                    ))
                  case None => Left(s"Unsupported conjunction '$normalized' for column '$targetColumn'")
              case _ => Left(s"Unable to interpret combined check '$normalized' for column '$targetColumn'")
          case None => parseSingle(normalized, columnKey, targetColumn)

      result

  private def arrangeBounds(
    op1: ComparisonOperator,
    value1: LiteralValue,
    op2: ComparisonOperator,
    value2: LiteralValue,
  ): Option[(LiteralValue, Boolean, LiteralValue, Boolean)] =
    val lower1 = isLowerOperator(op1)
    val upper1 = isUpperOperator(op1)
    val lower2 = isLowerOperator(op2)
    val upper2 = isUpperOperator(op2)

    (lower1, upper1, lower2, upper2) match
      case (true, false, false, true) => Some((value1, isInclusiveLower(op1), value2, isInclusiveUpper(op2)))
      case (false, true, true, false) => Some((value2, isInclusiveLower(op2), value1, isInclusiveUpper(op1)))
      case _                         => None

  private def isLowerOperator(op: ComparisonOperator): Boolean =
    op == ComparisonOperator.GreaterThan || op == ComparisonOperator.GreaterThanOrEqual

  private def isUpperOperator(op: ComparisonOperator): Boolean =
    op == ComparisonOperator.LessThan || op == ComparisonOperator.LessThanOrEqual

  private def isInclusiveLower(op: ComparisonOperator): Boolean =
    op == ComparisonOperator.GreaterThanOrEqual

  private def isInclusiveUpper(op: ComparisonOperator): Boolean =
    op == ComparisonOperator.LessThanOrEqual

  private def parseSingle(expr: String, columnKey: String, targetColumn: String): Either[String, Parsed] =
    val comparisonPattern = """(?i)^(.+?)\s*(>=|<=|<>|!=|=|>|<)\s*(.+)$""".r
    val betweenPattern    = """(?i)^(.+?)\s+BETWEEN\s+(.+?)\s+AND\s+(.+)$""".r
    val lengthPattern     = """(?i)^(char_length|length)\s*\(\s*(.+?)\s*\)\s*(>=|<=|=|>|<)\s*(\d+)\s*$""".r
    val inPattern         = """(?i)^(.+?)\s+(NOT\s+)?IN\s*\((.+)\)\s*$""".r
    val isNullPattern     = """(?i)^(.+?)\s+IS\s+(NOT\s+)?NULL$""".r

    expr match
      case isNullPattern(rawColumn, notGroup) if matchesColumn(rawColumn, columnKey) =>
        val negated = Option(notGroup).exists(_.trim.nonEmpty)
        Right(Parsed(ValidationPlan.NotNull(targetColumn, negated), expr))

      case betweenPattern(rawColumn, rawLower, rawUpper) if matchesColumn(rawColumn, columnKey) =>
        val lower = parseLiteral(rawLower)
        val upper = parseLiteral(rawUpper)
        Right(
          Parsed(
            ValidationPlan.Between(targetColumn, lower, upper, lowerInclusive = true, upperInclusive = true),
            expr,
          ),
        )

      case inPattern(rawColumn, notGroup, rawList) if matchesColumn(rawColumn, columnKey) =>
        val negated = Option(notGroup).exists(_.trim.nonEmpty)
        val values  = splitCommaSeparated(rawList).map(parseLiteral)
        Right(Parsed(ValidationPlan.Inclusion(targetColumn, values, negated), expr))

      case lengthPattern(_, rawColumn, operator, rawValue) if matchesColumn(rawColumn, columnKey) =>
        val op     = parseOperator(operator)
        val length = Try(rawValue.trim.toInt).toOption.getOrElse(0)
        Right(Parsed(ValidationPlan.LengthComparison(targetColumn, op, length), expr))

      case comparisonPattern(rawLeft, operatorSymbol, rawRight) if matchesColumn(rawLeft, columnKey) =>
        val operator = parseOperator(operatorSymbol)
        val value    = parseLiteral(rawRight)
        Right(Parsed(ValidationPlan.NumericComparison(targetColumn, operator, value), expr))

      case _ => Left(s"Unsupported check expression '$expr' for column '$targetColumn'")

  private def parseOperator(symbol: String): ComparisonOperator =
    symbol.trim match
      case ">"  => ComparisonOperator.GreaterThan
      case ">=" => ComparisonOperator.GreaterThanOrEqual
      case "<"  => ComparisonOperator.LessThan
      case "<=" => ComparisonOperator.LessThanOrEqual
      case "="  => ComparisonOperator.Equal
      case "<>" => ComparisonOperator.NotEqual
      case "!=" => ComparisonOperator.NotEqual
      case other => throw new IllegalArgumentException(s"Unsupported comparison operator '$other'")

  private def parseLiteral(raw: String): LiteralValue =
    val withoutCasts = stripTypeCasts(stripOuterParentheses(raw.trim))
    val lower        = withoutCasts.toLowerCase(Locale.ROOT)
    if lower == "true" then LiteralValue.BooleanLiteral(true)
    else if lower == "false" then LiteralValue.BooleanLiteral(false)
    else if withoutCasts.startsWith("'") && withoutCasts.endsWith("'") then
      LiteralValue.StringLiteral(parseStringLiteral(withoutCasts))
    else
      Try(BigDecimal(withoutCasts)).map(LiteralValue.Numeric.apply).getOrElse(LiteralValue.Raw(withoutCasts))

  private def parseStringLiteral(token: String): String =
    val stripped = token.stripPrefix("'").stripSuffix("'")
    stripped.replace("''", "'")

  private def splitCommaSeparated(input: String): Seq[String] =
    val items  = ListBuffer.empty[String]
    val buffer = new StringBuilder

    var idx           = 0
    var depth         = 0
    var inQuote       = false
    val length        = input.length

    while idx < length do
      val ch = input.charAt(idx)
      ch match
        case '(' if !inQuote =>
          depth += 1
          buffer.append(ch)
        case ')' if !inQuote && depth > 0 =>
          depth -= 1
          buffer.append(ch)
        case ',' if !inQuote && depth == 0 =>
          items += buffer.toString.trim
          buffer.clear()
        case '\'' =>
          if inQuote && idx + 1 < length && input.charAt(idx + 1) == '\'' then
            buffer.append(''')
            idx += 1
          else
            inQuote = !inQuote
            buffer.append(ch)
        case other => buffer.append(other)
      idx += 1

    if buffer.nonEmpty then items += buffer.toString.trim

    items.toSeq.filter(_.nonEmpty)

  private def stripTypeCasts(input: String): String =
    val idx = input.indexOf("::")
    if idx >= 0 then input.substring(0, idx) else input

  private def matchesColumn(raw: String, columnKey: String): Boolean =
    val identifier = normalizeIdentifier(raw)
    identifier.exists(_.toLowerCase(Locale.ROOT) == columnKey)

  private def normalizeIdentifier(identifier: String): Option[String] =
    val trimmed = stripTypeCasts(stripOuterParentheses(identifier.trim))
    if trimmed.isEmpty then None
    else
      val parts = trimmed.split("\\.").toList
      parts.lastOption.map(stripQuotes)

  private def stripQuotes(value: String): String =
    val trimmed = value.trim
    if trimmed.startsWith("\"") && trimmed.endsWith("\"") then
      trimmed.substring(1, trimmed.length - 1)
    else trimmed

  private def normalize(definition: String): String =
    val noCheck =
      if definition.trim.toUpperCase(Locale.ROOT).startsWith("CHECK") then
        definition.trim.drop(5).trim
      else definition.trim
    stripOuterParentheses(noCheck)

  private def splitTopLevel(input: String, keyword: String): Option[(String, String)] =
    val upperKeyword = keyword.toUpperCase(Locale.ROOT)
    val upperInput   = input.toUpperCase(Locale.ROOT)

    var depth = 0
    var idx   = 0
    while idx <= upperInput.length - upperKeyword.length do
      upperInput.charAt(idx) match
        case '(' =>
          depth += 1
          idx += 1
        case ')' =>
          depth = math.max(0, depth - 1)
          idx += 1
        case _ if depth == 0 && upperInput.startsWith(upperKeyword, idx) && isBoundary(upperInput, idx, upperKeyword.length) =>
          val left  = input.substring(0, idx).trim
          val right = input.substring(idx + upperKeyword.length).trim
          if left.nonEmpty && right.nonEmpty then return Some((left, right))
          else idx += upperKeyword.length
        case _ => idx += 1

    None

  private def isBoundary(s: String, start: Int, length: Int): Boolean =
    val beforeOk = start == 0 || s.charAt(start - 1).isWhitespace || s.charAt(start - 1) == ')'
    val afterIdx = start + length
    val afterOk  = afterIdx >= s.length || s.charAt(afterIdx).isWhitespace || s.charAt(afterIdx) == '('
    beforeOk && afterOk

  private def stripOuterParentheses(input: String): String =
    @tailrec
    def loop(str: String): String =
      val trimmed = str.trim
      if trimmed.startsWith("(") && trimmed.endsWith(")") && isBalanced(trimmed.substring(1, trimmed.length - 1)) then
        loop(trimmed.substring(1, trimmed.length - 1))
      else trimmed
    loop(input)

  private def isBalanced(input: String): Boolean =
    var depth = 0
    var idx   = 0
    while idx < input.length do
      input.charAt(idx) match
        case '(' => depth += 1
        case ')' =>
          depth -= 1
          if depth < 0 then return false
        case _ =>
      idx += 1
    depth == 0
