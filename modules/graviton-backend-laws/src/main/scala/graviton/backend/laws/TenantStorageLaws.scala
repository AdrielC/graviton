package graviton.backend.laws

import graviton.runtime.stores.{BlobStore, StoreError}
import graviton.runtime.upload.TenantId
import graviton.streams.BoundedByteStream
import zio.*
import zio.stream.ZStream
import zio.test.*

/**
 * Backend-owned fixture for the tenant storage contract.
 *
 * The isolated store must use distinct physical block namespaces for the two
 * configured tenants. The shared store must use one explicitly configured
 * physical block namespace while retaining separate tenant manifest indexes.
 */
trait TenantStorageFixture:
  def tenantA: TenantId
  def tenantB: TenantId
  def unknownTenant: TenantId
  def isolatedStore: BlobStore
  def sharedStore: BlobStore

  def asTenant[R, E, A](tenantId: TenantId)(effect: ZIO[R, E, A]): ZIO[R, E, A]
  def withoutTenant[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A]

/** Reusable fail-closed isolation and opt-in shared-deduplication contract. */
object TenantStorageLaws:
  private val Payload = Chunk.fromArray(Array.tabulate[Byte](256 * 1024)(index => ((index * 17) % 251).toByte))
  private val Other   = Chunk.fromArray(Array.tabulate[Byte](192 * 1024)(index => ((index * 43) % 251).toByte))

  /** `acquire` must return empty isolated and shared stores retained by the supplied scope. */
  def suite(backendName: String)(acquire: ZIO[Scope, StoreError, TenantStorageFixture]): Spec[TestEnvironment, StoreError] =
    zio.test.suite(s"$backendName tenant storage")(
      zio.test.test("rejects a missing tenant before pulling upload bytes") {
        withFixture(acquire) { fixture =>
          for
            pulls <- Ref.make(0)
            source = ZStream.fromZIO(pulls.updateAndGet(_ + 1)).flatMap(_ => ZStream.fromChunk(Payload))
            exit  <- fixture.withoutTenant(source.run(fixture.isolatedStore.put())).exit
            count <- pulls.get
          yield assertTrue(
            count == 0,
            exit.foldExit(_.failureOption.exists(_.isInstanceOf[StoreError.MissingTenantContext]), _ => false),
          )
        }
      },
      zio.test.test("rejects an unconfigured tenant") {
        withFixture(acquire) { fixture =>
          fixture.asTenant(fixture.unknownTenant)(fixture.isolatedStore.healthCheck).exit.map { exit =>
            assertTrue(exit.foldExit(_.failureOption.exists(_.isInstanceOf[StoreError.TenantNotConfigured]), _ => false))
          }
        }
      },
      zio.test.test("keeps equal blocks physically isolated by default") {
        withFixture(acquire) { fixture =>
          for
            first  <- fixture.asTenant(fixture.tenantA)(upload(fixture.isolatedStore, Payload))
            hidden <- fixture.asTenant(fixture.tenantB)(fixture.isolatedStore.get(first.key).runDrain.either)
            second <- fixture.asTenant(fixture.tenantB)(upload(fixture.isolatedStore, Payload))
          yield assertTrue(
            hidden.isLeft,
            first.key == second.key,
            first.stats.freshBlocks > 0,
            first.stats.duplicateBlocks == 0,
            second.stats.freshBlocks > 0,
            second.stats.duplicateBlocks == 0,
          )
        }
      },
      zio.test.test("shares blocks only inside an explicit domain and never shares manifests") {
        withFixture(acquire) { fixture =>
          for
            first       <- fixture.asTenant(fixture.tenantA)(upload(fixture.sharedStore, Payload))
            beforeShare <- fixture.asTenant(fixture.tenantB)(fixture.sharedStore.get(first.key).runDrain.either)
            second      <- fixture.asTenant(fixture.tenantB)(upload(fixture.sharedStore, Payload))
            read        <- fixture.asTenant(fixture.tenantB)(collectFixture(fixture.sharedStore.get(second.key)))
            countA      <- fixture.asTenant(fixture.tenantA)(fixture.sharedStore.streamInventory.runCount)
            countB      <- fixture.asTenant(fixture.tenantB)(fixture.sharedStore.streamInventory.runCount)
          yield assertTrue(
            beforeShare.isLeft,
            first.key == second.key,
            second.stats.freshBlocks == 0,
            second.stats.duplicateBlocks == second.stats.blockCount,
            read == Payload,
            countA == 1L,
            countB == 1L,
          )
        }
      },
      zio.test.test("tenant deletion cannot revoke another tenant's shared blob") {
        withFixture(acquire) { fixture =>
          for
            first  <- fixture.asTenant(fixture.tenantA)(upload(fixture.sharedStore, Payload))
            _      <- fixture.asTenant(fixture.tenantB)(upload(fixture.sharedStore, Payload))
            _      <- fixture.asTenant(fixture.tenantA)(fixture.sharedStore.delete(first.key))
            hidden <- fixture.asTenant(fixture.tenantA)(fixture.sharedStore.stat(first.key))
            read   <- fixture.asTenant(fixture.tenantB)(collectFixture(fixture.sharedStore.get(first.key)))
          yield assertTrue(hidden.isEmpty, read == Payload)
        }
      },
      zio.test.test("concurrent tenants cannot cross-list manifests") {
        withFixture(acquire) { fixture =>
          for
            written <- ZIO.collectAllPar(
                         Chunk(
                           fixture.asTenant(fixture.tenantA)(upload(fixture.sharedStore, Payload)),
                           fixture.asTenant(fixture.tenantB)(upload(fixture.sharedStore, Other)),
                         )
                       )
            listA   <- fixture.asTenant(fixture.tenantA)(inventorySummary(fixture.sharedStore))
            listB   <- fixture.asTenant(fixture.tenantB)(inventorySummary(fixture.sharedStore))
          yield assertTrue(
            written.map(_.key).distinct.length == 2,
            listA == (1L -> Some(written(0).key)),
            listB == (1L -> Some(written(1).key)),
          )
        }
      },
    ) @@ TestAspect.sequential

  private def withFixture[A](
    acquire: ZIO[Scope, StoreError, TenantStorageFixture]
  )(use: TenantStorageFixture => IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped(acquire.flatMap(use))

  private def upload(store: BlobStore, bytes: Chunk[Byte]) =
    ZStream.fromChunk(bytes).run(store.put())

  private def collectFixture(stream: ZStream[Any, StoreError, Byte]) =
    BoundedByteStream.collectInMemory(stream).mapError {
      case error: StoreError              => error
      case error: BoundedByteStream.Error => StoreError.CorruptData(graviton.runtime.stores.StoreOperation.GetBlob, error.getMessage, error)
    }

  private def inventorySummary(store: BlobStore) =
    store.streamInventory.runFold((0L, Option.empty[graviton.core.keys.BinaryKey.Blob])) { case ((count, first), listing) =>
      (java.lang.Math.addExact(count, 1L), first.orElse(Some(listing.key)))
    }
