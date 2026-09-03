# Runtime Ports (Current APIs)

Graviton’s runtime layer defines a small set of interfaces (“ports”). Backends implement these ports so the rest of the system can stay storage-agnostic.

This page documents the **current** port shapes in `modules/graviton-runtime` (not aspirational designs).

## `BlobStore`

The primary interface for ingest + retrieval of logical blobs.

- **Write**: `put(plan)` returns a `ZSink` you run a `ZStream[Byte]` into.
- **Read**: `get(key)` returns a `ZStream[Byte]` you can stream to files, HTTP responses, etc.
- **Inventory page**: `inventoryPage(after, limit)` delegates to the backend's stable native order and returns an opaque continuation cursor.
- **Inventory stream**: `streamInventory` follows pages lazily without retaining repository-scale state.

The error channel is `StoreError`, not `Throwable`. Each failure identifies the operation, preserves a diagnostic cause where one exists, and exposes an explicit `retryable` classification.

See `graviton.runtime.stores.BlobStore`.

## `BlockStore`

Stores canonical blocks produced by chunkers/hashing and returns the manifest describing those blocks.

- **Write**: `putBlocks(plan)` returns a `ZSink` you run `CanonicalBlock`s into.
- **Read**: `get(key)` streams a stored canonical block’s bytes.

See `graviton.runtime.stores.BlockStore`.

## `ImmutableObjectStore` / `MutableObjectStore`

These ports model *locator-addressed* storage (think “object storage”), independent of Graviton’s content keys.

- `ImmutableObjectStore` supports `head`, `list`, and `get`.
- `MutableObjectStore` adds `put`, `delete`, and `copy`.

Their public streams, sinks, and effects also use `StoreError`.

See `graviton.runtime.stores.ImmutableObjectStore` and `graviton.runtime.stores.MutableObjectStore`.

## `ReplicaIndex`

Tracks which locators contain a given logical blob key.

Current shape:

- `replicas(key): IO[StoreError, Set[BlobLocator]]`
- `update(key, locators): IO[StoreError, Unit]`

See `graviton.runtime.indexes.ReplicaIndex`.

## `KeyValueStore`

A minimal KV port used for internal indexes/configurable metadata storage.

- `put(key: KvKey, value: KvValue)`
- `get(key: KvKey)`
- `delete(key: KvKey)`

`KvKey` is an Iron-refined non-empty key capped at 1024 characters. `KvValue` is a `Chunk[Byte]` capped at 32 MiB.

See `graviton.runtime.kv.KeyValueStore`.

## `MaintenanceCoordinator`

This port coordinates ordinary blob work with destructive repository maintenance across independent manifest and block stores.

- `operationPermit` is shared and scoped across the complete sink or stream lifetime.
- `maintenanceLease` is exclusive and scoped across the complete maintenance run.
- `healthCheck` verifies that the coordination backend can be reached.

`CoordinatedBlobStore` decorates any `BlobStore` and applies the shared permit to uploads, downloads, metadata, inventory, inspection, and deletion. `GarbageCollection.live` requires the same coordinator explicitly. Filesystem and PostgreSQL implementations provide cross-process coordination; the in-process implementation is for embedded memory stores and deterministic tests.

## `TransferBudget`

`TransferFootprint` is the algebra of named live-byte owners for one operation. Queues, chunkers, ordered persistence, request bodies, replicas, and erasure coding contribute independently, then `TransferBudget` reserves the composed total exactly once. Packaged wiring acquires process bytes, tenant bytes and tenant concurrency, then backend concurrency in a fixed order. Waiting is interruptible, registry cardinality is bounded, and scope releases every permit on success, failure, or interruption. Inline CAS and resumable staging share the process budget.

## `ManifestIntegrity` / `ManifestKeyService`

`ManifestIntegrity` incrementally authenticates manifest semantics without collecting entries. Its version-3 proof binds blob identity, total size, chunker, count, metadata, and ordered block keys and spans into a version-1 Merkle B-tree root. Leaves and branches have a maximum fanout of 64, and child summaries authenticate their index and byte ranges. `ManifestKeyService` is the ZIO boundary for local HMAC, KMS, or HSM implementations. Built-in filesystem and PostgreSQL repositories can require root and signature verification before returning any block reference, so block storage is not touched when metadata authentication fails.

## `TenantStoreProvider` / `TenantContext`

`TenantStoreProvider` resolves an authenticated `TenantId` to one long-lived logical store and explicit deduplication route. `TenantContext` carries that identity regionally through a parent-preserving `FiberRef`. `ContextualTenantBlobStore` resolves once per logical operation and fails before pulling bytes when context is missing or unknown. Isolated storage is the default; shared physical blocks require an explicit trust domain and an independent ZIO Config opt-in. See [Multi-tenant storage](./multi-tenancy.md).

## `ManifestReferenceSource`

`ManifestReferenceSource` streams every live block reference for one physical block domain. `GarbageCollector.forStorageDomain` consumes a non-empty set of tenant manifest repositories sequentially so shared-domain GC cannot quarantine a block that is live for another tenant.

## `RepairJournal`

`RepairJournal` stores the next repair offset and unresolved per-block failures. Filesystem deployments use atomic files below `cas/repair`; shared deployments use PostgreSQL rows. Dead letters are streamed, attempts are monotonic and bounded, and a successful convergence removes the unresolved entry.

## Reference implementations

- **In-memory block store**: `InMemoryBlockStore` in `graviton.runtime.stores` (main sources) with a `layer` helper.
- **Test-only blob store**: `InMemoryBlobStore` lives under **`src/test`** — useful in tests, not part of the published main API.
- **App-friendly in-memory stack**: `Graviton.inMemory()` builds a coordinated `CasBlobStore` over `InMemoryBlockStore` with an inline manifest repo.
- **Backend adapters**: PostgreSQL object/KV/replica stores, S3 object/CAS stores, and RocksDB KV under `modules/backend/*`

For a current inventory and status notes, see **[Storage backends](./backends.md)**.
