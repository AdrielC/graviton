package graviton.core.macros

import scala.quoted.*

/** TASTy macros backed by the Hearth macro stdlib.
  *
  * This is intentionally small: it proves the wiring and provides a useful building block
  * for richer compile-time diagnostics in other macros.
  */
object HearthDebug:

  /** Returns a stable-ish compile-time type name for `A`.
    *
    * You can use this for better error messages from other macros.
    */
  inline def typeName[A]: String = ${ typeNameImpl[A] }

  private def typeNameImpl[A: Type](using Quotes): Expr[String] =
    // `Type.show` is backed by TASTy reflection; this is compile-time computed.
    Expr(Type.show[A])

