package graviton.core.scan

import kyo.Record
import kyo.Record.`~`

/**
 * Scan state/output records are modeled using `kyo.Record`.
 *
 * This replaces the older tuple-based "named tuple" record encoding, while still
 * allowing ergonomic composition via intersection types like:
 *
 * `Record[("count" ~ Long) & ("bytes" ~ Long)]`
 */

type Field[K <: String & Singleton, V] = K ~ V
type Rec[Fields]                       = Record[Fields]

object rec:
  inline def empty: Record[Any] = Record.empty

  inline def field[K <: String & Singleton, V](k: K, v: V): Record[K ~ V] =
    (Record.empty & (k ~ v)).asInstanceOf[Record[K ~ V]]
