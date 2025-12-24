package graviton.runtime.legacy

import zio.*

trait LegacyCatalog:
  def resolve(id: LegacyId): IO[LegacyRepoError.CatalogError, LegacyDescriptor]
