package graviton.core.attributes

import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator

/**
 * The result of writing a blob to a store.
 *
 * Contains the content-addressed key, the backend locator, and the
 * advertised/confirmed attributes collected during ingest.
 */
final case class BlobWriteResult(
  key: BinaryKey,
  locator: BlobLocator,
  attributes: BinaryAttributes,
)
