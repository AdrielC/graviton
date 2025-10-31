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
