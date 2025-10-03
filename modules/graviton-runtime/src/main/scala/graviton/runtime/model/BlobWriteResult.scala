package graviton.runtime.model

import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator

final case class BlobWriteResult(key: BinaryKey, locator: BlobLocator, attributes: Map[String, String])
