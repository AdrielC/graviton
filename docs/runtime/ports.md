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

- `put(key: String, value: Array[Byte])`
- `get(key: String)`
- `delete(key: String)`

See `graviton.runtime.kv.KeyValueStore`.

## Reference implementations

- **In-memory stores**: `InMemoryBlobStore` and `InMemoryBlockStore` in `modules/graviton-runtime`
- **Backend scaffolding**: Postgres/S3/Rocks implementations under `modules/backend/*`

For a current inventory and status notes, see **[Storage backends](./backends.md)**.

