package graviton.core.macros

import scala.quoted.*

/**
 * Small TASTy macro utilities for working with case classes at compile-time.
 *
 * This is useful for:
 * - building better error messages
 * - wiring codecs/metrics with stable field-name lists
 * - avoiding stringly-typed duplication
 */
object CaseClassFields:

  /** Returns the case class field names for `A` at compile time. */
  inline def names[A]: List[String] = ${ namesImpl[A] }

  private def namesImpl[A: Type](using Quotes): Expr[List[String]] =
    import quotes.reflect.*

    val sym = TypeRepr.of[A].typeSymbol
    if !sym.flags.is(Flags.Case) then report.errorAndAbort(s"CaseClassFields.names: expected a case class, got ${sym.fullName}")

    val fields = sym.caseFields.map(_.name)
    Expr.ofList(fields.map(Expr(_)))
