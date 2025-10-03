package graviton.core.manifest

import graviton.core.keys.BinaryKey
import graviton.core.ranges.Span

final case class ManifestEntry(key: BinaryKey, span: Span[Long], attributes: Map[String, String])

final case class Manifest(entries: List[ManifestEntry], size: Long)
