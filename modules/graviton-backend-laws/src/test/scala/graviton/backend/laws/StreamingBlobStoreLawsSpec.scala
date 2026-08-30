package graviton.backend.laws

import graviton.runtime.stores.{CasBlobStore, InMemoryBlobManifestRepo, InMemoryBlockStore, StoreError}
import zio.*
import zio.test.*

object StreamingBlobStoreLawsSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment, StoreError] =
    StreamingBlobStoreLaws.suite("in-memory CAS") {
      for
        blocks    <- InMemoryBlockStore.make
        manifests <- InMemoryBlobManifestRepo.make
      yield new CasBlobStore(blocks, manifests)
    }
