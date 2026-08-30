# Backend Laws

`graviton-backend-laws_3` is the executable contract for a logical `BlobStore`. It is a published test-support module, not an empty marker artifact.

```scala
libraryDependencies ++= Seq(
  "io.github.adrielc" %% "graviton-runtime"      % gravitonVersion,
  "io.github.adrielc" %% "graviton-backend-laws" % gravitonVersion % Test,
  "dev.zio" %% "zio-test-sbt"          % zioVersion      % Test,
)
```

Mount the laws with a scoped acquisition effect that creates an isolated empty store:

```scala
import graviton.backend.laws.{BlobStoreLaws, CrashConsistencyLaws, TenantStorageLaws}
import graviton.runtime.stores.{BlobStore, StoreError}
import zio.*
import zio.test.*

object MyBackendSpec extends ZIOSpecDefault:
  val freshStore: ZIO[Scope, StoreError, BlobStore] =
    MyBackend.scoped(testConfiguration)

  override def spec = BlobStoreLaws.suite("my backend")(freshStore)
```

The current contract proves:

- streamed write and byte-exact read preserve content identity
- duplicate writes are idempotent and produce one logical inventory entry
- bounded range reads return exactly the requested span
- opaque pages have no duplicate or omitted entries
- delete makes metadata and bytes unreachable
- interruption does not publish a partial logical blob

## CrashLab

`CrashConsistencyLaws` is a reusable restart contract. An adapter supplies a scoped `CrashBackend` that can rebuild its store over the same durable state. `FaultPlan` validates a bounded set of deterministic operation/phase/occurrence rules. `FaultController` records a bounded trace and can fail, delay through the ZIO clock, or interrupt the calling fiber.

The published crash contract proves:

- acknowledged bytes survive reconstruction;
- failure before manifest publication leaves no logical blob;
- a lost acknowledgement after publication can be retried idempotently;
- interrupted input never publishes after restart;
- concurrent occurrence accounting triggers exactly once;
- delayed faults are deterministic under `TestClock`;
- retained fault traces cannot grow without bound.

`FaultingBlockStore`, `FaultingBlobManifestRepo`, and `FaultingBlobStore` decorate streams lazily. They do not collect upload or download bodies. The filesystem self-test reconstructs real filesystem stores, but it is still an in-process fault model. It does not prove kernel, disk-controller, power-loss, database-failover, or object-provider behavior.

## Tenant storage laws

`TenantStorageLaws` accepts a scoped fixture with an isolated store, an explicitly shared-domain store, two configured tenants, and one unknown tenant. It proves fail-before-pull routing, private physical reuse by default, tenant-scoped manifests in a shared block domain, deletion independence, and concurrent inventory isolation.

Adapters that share a block domain must also mount domain-wide maintenance through `GarbageCollector.forStorageDomain`. The collector's manifest set must include every tenant that can reference that domain.

The Graviton build applies the base suite to both in-memory and filesystem CAS, the crash suite to a reconstructed filesystem CAS, and the tenant suite to isolated and shared in-memory topologies. PostgreSQL, S3, Ceph, and third-party adapters should mount the relevant suites in their integration environment, then add process-kill, capacity, corruption, credential, failover, and restore drills. Passing the laws establishes logical behavior, not provider capacity, disaster recovery, or service-level guarantees.
