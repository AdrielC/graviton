package graviton.core.macros

import graviton.core.macros.internal.CaseClassFieldsMacrosImpl
import hearth.MacroCommonsScala3

/** Scala 3 adapter: wires `Quotes` into the shared cross-quotes implementation. */
private[macros] trait CaseClassFieldsCompanionCompat:

  /** Returns the case class field names for `A` at compile time. */
  inline def names[A]: List[String] = ${ CaseClassFieldsCompanionCompat.namesImpl[A] }

private[macros] object CaseClassFieldsCompanionCompat:

  def namesImpl[A: scala.quoted.Type](using quotes: scala.quoted.Quotes): scala.quoted.Expr[List[String]] =
    (new MacroCommonsScala3(using quotes) with CaseClassFieldsMacrosImpl).deriveNames[A]
