package graviton.core.macros

import kyo.Tag

import scala.quoted.*

/**
 * Compile-time-only retyping for Kyo tags representing Iron's path-dependent
 * `T` member by the refined companion's singleton identity.
 *
 * Kyo's `Tag` parameter is phantom at runtime. The call site derives the
 * concrete companion tag first; this macro changes only the compile-time
 * witness type and emits no runtime cast.
 */
private[core] object RefinedKyoTag:

  inline def fromCompanion[A, Owner <: Singleton](inline tag: Tag[Owner]): Tag[A] =
    ${ fromCompanionImpl[A, Owner]('tag) }

  private def fromCompanionImpl[A: Type, Owner <: Singleton: Type](tag: Expr[Tag[Owner]])(using Quotes): Expr[Tag[A]] =
    import quotes.reflect.*

    val ownerType = TypeRepr.of[Owner]
    if !(ownerType <:< TypeRepr.of[scala.Singleton]) then
      report.errorAndAbort(s"Refined Kyo tag owner must be a singleton, received ${ownerType.show}", tag)

    // Expr's parameter is erased after macro expansion. This is the sole
    // trusted witness conversion, and it cannot emit a runtime CHECKCAST.
    tag.asInstanceOf[Expr[Tag[A]]]
