# Storage Backends

Graviton keeps storage contracts in `graviton-runtime` and vendor code in backend modules. This table distinguishes an operational path from a useful adapter and from a scaffold.

| Module | Current capability | Status |
| --- | --- | --- |
| `graviton-runtime` | In-memory CAS plus filesystem blocks and versioned filesystem manifests | Operational and covered by restart tests |
| `backend/graviton-pg` | Transactional PostgreSQL blob manifests | Operational S3/MinIO server manifest path; several generic ports remain scaffolds |
| `backend/graviton-s3` | S3/MinIO content-addressed block `put/get/exists` | Operational server block path with integration coverage |
| `backend/graviton-rocks` | Scoped RocksDB `put/get/delete` | Durable KV adapter with reopen coverage; not a CAS backend |

## Local filesystem CAS

`Graviton.fs(root)` composes:

- `FsBlockStore`, which writes blocks below `cas/blocks/<algorithm>/...`
- `FsBlobManifestRepo`, which writes bounded, versioned `FramedManifest` files below `cas/manifests/<algorithm>/...`
- `CasBlobStore`, which streams ingestion, retrieval, metadata, verification, and manifest deletion

Manifest writes use a temporary file and atomic move where the filesystem supports it. A fresh `Graviton.fs(root)` instance can retrieve blobs written by an earlier process. Run `./scripts/verify-local-lifecycle.sh` to exercise that behavior through separate CLI JVMs.

Deleting a blob removes its manifest. Shared content-addressed blocks remain available for other manifests. Garbage collection is a separate two-pass lifecycle with minimum-age filtering, dry-run, reversible quarantine, restore, and delayed purge. The CLI exposes preview and quarantine for filesystem stores; S3 exposes the maintenance API but not an operator CLI yet.

## PostgreSQL manifests

`PgBlobManifestRepo` persists blob identity, ordered block spans, and ingestion time in a transaction. The server combines it with `S3BlockStore` for the S3/MinIO deployment path. The default filesystem server uses `FsBlobManifestRepo` and does not require PostgreSQL.

These PostgreSQL types are not production storage implementations yet:

- `PgImmutableObjectStore`
- `PgMutableObjectStore`
- `PgKeyValueStore`
- `PgReplicaIndex`

`PgRangeTracker` does implement in-process range merging with a pluggable KV persistence hook, but its durability depends on the supplied `KeyValueStore`.

See [Postgres schema notes](../ops/postgres-schema.md) for the database layout.

## S3 and MinIO

`S3BlockStore` implements content-addressed block writes, reads, existence checks, and duplicate detection. The server uses it with `PgBlobManifestRepo` for the S3/MinIO deployment path.

`S3BlobStore` is a separate full-object adapter. It implements multipart upload, retrieval, and deletion, but `stat` is not implemented. `S3ImmutableObjectStore` and `S3MutableObjectStore` remain interface scaffolds. Those types are not part of the server's CAS path.

## RocksDB

`RocksKeyValueStore` is a real embedded KV adapter backed by RocksDB. Its layer owns and closes both the database and native options, and tests prove values survive closing and reopening the database.

It implements `KeyValueStore`, not `BlobStore`, `BlockStore`, or `BlobManifestRepo`. Treat it as a metadata primitive until a CAS-specific composition is added.

## Capability rule

A type existing in the repository does not make a backend operational. Graviton labels a path operational only when its methods perform real I/O, its resources have explicit lifecycles, and tests exercise the intended behavior.

See also [runtime ports](./ports.md), [replication](./replication.md), and [architecture](../architecture.md).
