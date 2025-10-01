package graviton.core

/**
 * Temporary alias during the rename from BinaryKey -> FileKey.
 * This keeps downstream code compiling while we migrate interfaces.
 */
type FileKey = BinaryKey

object FileKey:
  // Re-expose key constructors and nested types for ergonomic call sites
  export BinaryKey.{CasKey, WritableKey}
  export BinaryKey.WritableKey.{Rnd, Static, Scoped}
