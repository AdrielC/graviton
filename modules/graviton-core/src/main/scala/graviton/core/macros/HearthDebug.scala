package graviton.core.macros

/**
 * TASTy macros backed by the Hearth macro stdlib.
 *
 * This is intentionally small: it proves the wiring and provides a useful building block
 * for richer compile-time diagnostics in other macros.
 */
object HearthDebug extends HearthDebugCompanionCompat
