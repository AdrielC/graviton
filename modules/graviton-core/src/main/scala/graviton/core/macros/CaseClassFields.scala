package graviton.core.macros

/**
 * Small macro utilities for working with case classes at compile-time.
 *
 * The macro implementation lives in `internal/CaseClassFieldsMacrosImpl.scala` (cross-quotes / Hearth `MacroCommons`).
 * Scala-version-specific wiring lives in `scala-2/` and `scala-3/` adapters.
 */
object CaseClassFields extends CaseClassFieldsCompanionCompat:

  /** Import this to enable derivation logging. */
  sealed trait LogDerivation

  object debug:
    /** Enables logs from the macro expansion (useful when debugging derivation failures). */
    given logDerivation: LogDerivation = new LogDerivation {}
