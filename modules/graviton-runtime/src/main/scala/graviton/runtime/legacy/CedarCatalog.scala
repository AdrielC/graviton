package graviton.runtime.legacy

import zio.*

trait CedarCatalog:
  def resolve(id: LegacyId): IO[CedarLegacyError.CatalogError, LegacyDescriptor]
