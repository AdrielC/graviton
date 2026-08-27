# Runtime Ports (Current APIs)

Graviton’s runtime layer defines a small set of interfaces (“ports”). Backends implement these ports so the rest of the system can stay storage-agnostic.

This page documents the **current** port shapes in `modules/graviton-runtime` (not aspirational designs).

## `BlobStore`

The primary interface for ingest + retrieval of logical blobs.

- **Write**: `put(plan)` returns a `ZSink` you run a `ZStream[Byte]` into.
- **Read**: `get(key)` returns a `ZStream[Byte]` you can stream to files, HTTP responses, etc.

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

See `graviton.runtime.stores.ImmutableObjectStore` and `graviton.runtime.stores.MutableObjectStore`.

## `ReplicaIndex`

Tracks which locators contain a given logical blob key.

Current shape:

- `replicas(key): ZIO[Any, Throwable, Set[BlobLocator]]`
- `update(key, locators): ZIO[Any, Throwable, Unit]`

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

## Reference implementations

- **In-memory block store**: `InMemoryBlockStore` in `graviton.runtime.stores` (main sources) with a `layer` helper.
- **Test-only blob store**: `InMemoryBlobStore` lives under **`src/test`** — useful in tests, not part of the published main API.
- **App-friendly in-memory stack**: `Graviton.inMemory()` builds a coordinated `CasBlobStore` over `InMemoryBlockStore` with an inline manifest repo.
- **Backend adapters**: PostgreSQL object/KV/replica stores, S3 object/CAS stores, and RocksDB KV under `modules/backend/*`

For a current inventory and status notes, see **[Storage backends](./backends.md)**.
