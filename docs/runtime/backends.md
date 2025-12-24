# Storage Backends (Current Status)

Graviton’s runtime is built around small “ports” (interfaces) such as `BlobStore`, `BlockStore`, `ImmutableObjectStore`, and `MutableObjectStore`. Backend modules implement those ports.

This page describes **what exists in the repository today**, and what is still scaffolded.

## Summary

| Module | Implements | Status |
| --- | --- | --- |
| `modules/graviton-runtime` | `InMemoryBlobStore`, `InMemoryBlockStore` | ✅ usable for demos/tests |
| `modules/backend/graviton-pg` | `PgMutableObjectStore`, `PgImmutableObjectStore`, `PgReplicaIndex`, `PgKeyValueStore` | ⚠️ currently stubbed / placeholder behaviors |
| `modules/backend/graviton-s3` | `S3MutableObjectStore`, `S3ImmutableObjectStore`, `S3BlobStore` | ⚠️ object stores are placeholders; `S3BlobStore.put` is not implemented yet |
| `modules/backend/graviton-rocks` | `RocksKeyValueStore` | ⚠️ currently stubbed / placeholder behaviors |

## In-memory reference stores

- **Blob store**: `graviton.runtime.stores.InMemoryBlobStore` implements the current `BlobStore` API (streaming `put` via a `ZSink`, streaming `get` via a `ZStream`).
- **Block store**: `graviton.runtime.stores.InMemoryBlockStore` implements `BlockStore.putBlocks` and synthesizes manifest entries as blocks stream in.

These are the best place to start when you want an end-to-end example that matches current runtime types.

## PostgreSQL backend module (`graviton-pg`)

The Postgres module currently focuses on wiring points and schema evolution work:

- **Object-store ports**:
  - `PgImmutableObjectStore` / `PgMutableObjectStore`
- **Replica index port**:
  - `PgReplicaIndex` (currently returns an empty set and no-ops updates)
- **Key-value port**:
  - `PgKeyValueStore` (currently a stub)

If you are looking for the *actual* database schema used by Graviton’s evolving data model, see:
- **[Postgres schema notes](../ops/postgres-schema.md)**

## S3 backend module (`graviton-s3`)

The S3 module currently provides placeholder implementations of the object-store ports:

- `S3ImmutableObjectStore` / `S3MutableObjectStore`

It also defines `S3BlobStore`, but at the moment `put` intentionally fails with an `UnsupportedOperationException`. Until that is implemented, treat S3 as **non-functional for blob ingest**.

## Rocks backend module (`graviton-rocks`)

The Rocks module currently exposes a stub `RocksKeyValueStore` implementing `KeyValueStore`. It’s a wiring point for later work; it should not be treated as a durable backend yet.

## See also

- **[Runtime ports](./ports.md)** — the interfaces backends implement
- **[Replication](./replication.md)** — current replica index capabilities and roadmap
- **[Architecture](../architecture.md)** — where runtime vs backends fit in the module graph

