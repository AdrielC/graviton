package graviton.core.macros

import graviton.core.macros.internal.HearthDebugMacrosImpl
import hearth.MacroCommonsScala3

/** Scala 3 adapter: wires `Quotes` into the shared cross-quotes implementation. */
private[macros] trait HearthDebugCompanionCompat:

  /** Returns a stable-ish compile-time type name for `A`. */
  inline def typeName[A]: String = ${ HearthDebugCompanionCompat.typeNameImpl[A] }

private[macros] object HearthDebugCompanionCompat:

  def typeNameImpl[A: scala.quoted.Type](using quotes: scala.quoted.Quotes): scala.quoted.Expr[String] =
    (new MacroCommonsScala3(using quotes) with HearthDebugMacrosImpl).typeNameExpr[A]
