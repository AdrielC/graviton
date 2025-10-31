package dbcodegen

import munit.FunSuite

import CheckConstraintParser.*

final class CheckConstraintParserSuite extends FunSuite:

  test("parse simple numeric comparison") {
    val definition = """CHECK ((size > 0))"""
    val parsed     = CheckConstraintParser.parse(definition, "size")

    parsed match
      case Right(Parsed(ValidationPlan.NumericComparison(_, ComparisonOperator.GreaterThan, LiteralValue.Numeric(value)), _)) =>
        assertEquals(value, BigDecimal(0))
      case other => fail(s"Unexpected parse result: $other")
  }

  test("parse between comparison expressed as conjunction") {
    val definition = """CHECK ((age >= 18) AND (age <= 65))"""
    val parsed     = CheckConstraintParser.parse(definition, "age")

    parsed match
      case Right(Parsed(ValidationPlan.Between(_, lower, upper, lowerInclusive, upperInclusive), _)) =>
        assertEquals(lower, LiteralValue.Numeric(BigDecimal(18)))
        assertEquals(upper, LiteralValue.Numeric(BigDecimal(65)))
        assert(lowerInclusive)
        assert(upperInclusive)
      case other => fail(s"Unexpected parse result: $other")
  }

  test("parse inclusion list") {
    val definition = """CHECK ((status IN ('queued','running','completed')))"""
    val parsed     = CheckConstraintParser.parse(definition, "status")

    parsed match
      case Right(Parsed(ValidationPlan.Inclusion(_, values, negated), _)) =>
        assertEquals(negated, false)
        val rendered = values.collect { case LiteralValue.StringLiteral(str) => str }
        assertEquals(rendered, Seq("queued", "running", "completed"))
      case other => fail(s"Unexpected parse result: $other")
  }

  test("parse negated inclusion list") {
    val definition = """CHECK ((kind NOT IN ('alpha','beta')))"""
    val parsed     = CheckConstraintParser.parse(definition, "kind")

    parsed match
      case Right(Parsed(ValidationPlan.Inclusion(_, values, negated), _)) =>
        assertEquals(negated, true)
        val rendered = values.collect { case LiteralValue.StringLiteral(str) => str }
        assertEquals(rendered, Seq("alpha", "beta"))
      case other => fail(s"Unexpected parse result: $other")
  }

  test("parse length constraint") {
    val definition = """CHECK ((char_length(identifier) <= 12))"""
    val parsed     = CheckConstraintParser.parse(definition, "identifier")

    parsed match
      case Right(Parsed(ValidationPlan.LengthComparison(_, ComparisonOperator.LessThanOrEqual, value), _)) =>
        assertEquals(value, 12)
      case other => fail(s"Unexpected parse result: $other")
  }

  test("parse is not null constraint") {
    val definition = """CHECK ((payload IS NOT NULL))"""
    val parsed     = CheckConstraintParser.parse(definition, "payload")

    parsed match
      case Right(Parsed(ValidationPlan.NotNull(_, negated), _)) =>
        assertEquals(negated, true)
      case other => fail(s"Unexpected parse result: $other")
  }

  test("parse is null constraint") {
    val definition = """CHECK ((deleted_at IS NULL))"""
    val parsed     = CheckConstraintParser.parse(definition, "deleted_at")

    parsed match
      case Right(Parsed(ValidationPlan.NotNull(_, negated), _)) =>
        assertEquals(negated, false)
      case other => fail(s"Unexpected parse result: $other")
  }

  test("parse raw numeric equality") {
    val definition = """CHECK (attempts = 3)"""
    val parsed     = CheckConstraintParser.parse(definition, "attempts")

    parsed match
      case Right(Parsed(ValidationPlan.NumericComparison(_, ComparisonOperator.Equal, LiteralValue.Numeric(value)), _)) =>
        assertEquals(value, BigDecimal(3))
      case other => fail(s"Unexpected parse result: $other")
  }

  test("reject unsupported column expression") {
    val definition = """CHECK ((lower(name) = name))"""
    val parsed     = CheckConstraintParser.parse(definition, "name")

    parsed match
      case Left(message) => assert(message.contains("Unsupported"))
      case other         => fail(s"Expected parse failure but got $other")
  }

  test("reject empty definition") {
    val parsed = CheckConstraintParser.parse("", "field")

    assert(parsed.isLeft)
  }
