# Storage Backends (Current Status)

Graviton’s runtime is built around small “ports” (interfaces) such as `BlobStore`, `BlockStore`, `ImmutableObjectStore`, and `MutableObjectStore`. Backend modules implement those ports.

This page describes **what exists in the repository today**, and what is still scaffolded.

## Summary

| Module | Implements | Status |
| --- | --- | --- |
| `modules/graviton-runtime` | `InMemoryBlobStore`, `InMemoryBlockStore` | ✅ usable for demos/tests |
| `modules/backend/graviton-pg` | `PgBlobManifestRepo`, object-store ports, indexes | ✅ `PgBlobManifestRepo` is used by the server; other ports are still stubbed/placeholder |
| `modules/backend/graviton-s3` | `S3BlockStore`, object-store ports | ✅ `S3BlockStore` supports put/get/exists (used by the server); other ports are still evolving |
| `modules/backend/graviton-rocks` | `RocksKeyValueStore` | ⚠️ currently stubbed / placeholder behaviors |

## In-memory reference stores

- **Blob store**: `graviton.runtime.stores.InMemoryBlobStore` implements the current `BlobStore` API (streaming `put` via a `ZSink`, streaming `get` via a `ZStream`).
- **Block store**: `graviton.runtime.stores.InMemoryBlockStore` implements `BlockStore.putBlocks` and synthesizes manifest entries as blocks stream in.

These are the best place to start when you want an end-to-end example that matches current runtime types.

## PostgreSQL backend module (`graviton-pg`)

The Postgres module currently provides both real, server-used components and scaffolding:

- **Manifest storage (used by the server)**:
  - `PgBlobManifestRepo` persists blob manifests (the server composes it with a `BlockStore` into `CasBlobStore`).
- **Object-store + index ports (scaffolding)**:
  - `PgImmutableObjectStore`, `PgMutableObjectStore`, `PgReplicaIndex`, `PgKeyValueStore`, `PgRangeTracker` are present primarily as wiring points and will be filled in as the storage APIs stabilize.

If you are looking for the *actual* database schema used by Graviton’s evolving data model, see:
- **[Postgres schema notes](../ops/postgres-schema.md)**

## S3 backend module (`graviton-s3`)

The S3 module contains a functional `BlockStore` plus additional ports that are still evolving:

- **Block storage (used by the server)**:
  - `S3BlockStore` supports `putBlocks`, `get`, and `exists` and is designed to work against MinIO/S3-compatible endpoints.
- **Object-store ports (scaffolding)**:
  - `S3ImmutableObjectStore` / `S3MutableObjectStore` exist but are not the primary “happy path” today.

The server currently uses **PostgreSQL for manifests** plus **S3/MinIO for blocks**, composed via `CasBlobStore`.

## Rocks backend module (`graviton-rocks`)

The Rocks module currently exposes a stub `RocksKeyValueStore` implementing `KeyValueStore`. It’s a wiring point for later work; it should not be treated as a durable backend yet.

## See also

- **[Runtime ports](./ports.md)** — the interfaces backends implement
- **[Replication](./replication.md)** — current replica index capabilities and roadmap
- **[Architecture](../architecture.md)** — where runtime vs backends fit in the module graph

