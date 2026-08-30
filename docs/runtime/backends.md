# Storage Backends

Graviton keeps storage contracts in `graviton-runtime` and vendor code in backend modules. This table distinguishes an operational server composition from reusable storage adapters.

| Module | Current capability | Status |
| --- | --- | --- |
| `graviton-runtime` | In-memory CAS plus filesystem blocks and versioned filesystem manifests | Operational and covered by restart tests |
| `backend/graviton-pg` | Transactional manifests, object streams, bounded KV, ranges, and replicas | Operational adapters with embedded PostgreSQL coverage |
| `backend/graviton-s3` | S3/MinIO content-addressed blocks and multipart objects | Operational adapters with MinIO integration coverage |
| `backend/graviton-rocks` | Scoped RocksDB `put/get/delete` | Durable KV adapter with reopen coverage; not a CAS backend |

## Local filesystem CAS

`Graviton.fs(root)` composes:

- `FsBlockStore`, which writes blocks below `cas/blocks/<algorithm>/...`
- `FsBlobManifestRepo`, which writes and reads the bounded streaming `GVM2` contract below `cas/manifests/<algorithm>/...`
- `CasBlobStore`, which streams ingestion, retrieval, metadata, verification, and manifest deletion
- `FileMaintenanceCoordinator`, which holds shared or exclusive locks at `cas/.maintenance.lock`

`Graviton.fs(root)` wraps the CAS with `CoordinatedBlobStore`. The shared permit spans the complete upload or download stream, and exclusive maintenance waits for all active operations to finish. JVM-local instances share one refcounted operating-system lock, while independent processes coordinate through the lock file. Manifest writes use a temporary file and atomic move where the filesystem supports it. A fresh `Graviton.fs(root)` instance can retrieve blobs written by an earlier process. Run `./scripts/verify-local-lifecycle.sh` to exercise that behavior through separate CLI JVMs.

Deleting a blob removes its manifest. Shared content-addressed blocks remain available for other manifests. Garbage collection is a separate two-pass lifecycle with minimum-age filtering, dry-run, reversible quarantine, restore, and delayed purge. It streams the filesystem walk and manifest headers, spills an exact mark join to temporary disk, and keeps only one bounded digest partition in heap. The CLI exposes preview and quarantine for filesystem stores; S3 exposes the maintenance API but not an operator CLI yet.

## PostgreSQL manifests

`PgBlobManifestRepo` persists blob identity, ordered block spans, and ingestion time in a transaction. Inventory uses a keyset query over the canonical `(algorithm, digest, byte length)` identity and returns an opaque backend cursor. It therefore feeds HTTP pages, gRPC streams, repair, and garbage collection without loading all manifests. The server combines it with `S3BlockStore` and `PgMaintenanceCoordinator` for the S3/MinIO deployment path. Ordinary operations use a shared PostgreSQL session advisory lock and maintenance uses the exclusive form under one refined repository namespace. The default filesystem server uses `FsBlobManifestRepo` and does not require PostgreSQL.

Filesystem inventory walks the repository but retains only the smallest `limit + 1` paths after the cursor in a bounded max-heap. S3 full-object inventory delegates continuation to `ListObjectsV2` and respects the service's 1,000-key ceiling. Cursors are backend-owned and must be passed back unchanged.

`PgImmutableObjectStore` and `PgMutableObjectStore` stream objects through ordered rows capped at 1 MiB. Writes, replacement, copy, and cleanup are transactional. `PgKeyValueStore`, `PgReplicaIndex`, and `PgRangeTracker` use the same schema and propagate database failures rather than silently degrading to empty state.

`PgRepairJournal` persists one shared repair cursor and unresolved per-block failures with attempt counts. `FsRepairJournal` provides the same contract with atomic cursor replacement and one bounded failure record per block.

See [PostgreSQL storage](../ops/postgres-storage.md) for the database layout.

## S3-compatible object storage

`S3BlockStore` implements content-addressed block writes, reads, existence checks, and duplicate detection. The server uses it with `PgBlobManifestRepo` for the S3/MinIO deployment path.

`S3BlobStore` is a separate full-object adapter with adaptive bounded multipart upload, server-side multipart promotion for large content-addressed objects, retrieval, stat, inventory, inspection, deletion, health, and interrupted-upload abort. `S3ImmutableObjectStore` and `S3MutableObjectStore` provide prefix-isolated locator operations, including adaptive multipart put, list, copy, and delete. Those generic object types are reusable adapters rather than the server's block-oriented CAS path.

The adapter is exercised against MinIO in CI. A Ceph cluster can be addressed through [Ceph Object Gateway's S3-compatible endpoint](https://docs.ceph.com/en/latest/radosgw/) using the same endpoint, bucket, credential, and path-style settings. That is an architectural compatibility path, not a qualified native Ceph backend: this repository has no `librados` client, no RADOS implementation of `BlockStore`, and no Ceph integration test. A `ceph` locator value in the legacy combined PostgreSQL schema is descriptive metadata only.

Graviton performs content addressing and block reuse before the backend write, so equal blocks already share one RGW object key. Ceph's experimental full-object deduplication targets duplicate data behind distinct RGW objects; it is a separate storage-layer mechanism and is not enabled or managed by Graviton.

## RocksDB

`RocksKeyValueStore` is a real embedded KV adapter backed by RocksDB. Its layer owns and closes both the database and native options, and tests prove values survive closing and reopening the database.

It implements `KeyValueStore`, not `BlobStore`, `BlockStore`, or `BlobManifestRepo`. Treat it as a metadata primitive until a CAS-specific composition is added.

## Capability rule

A type existing in the repository does not make a backend operational. Graviton labels a path operational only when its methods perform real I/O, its resources have explicit lifecycles, and tests exercise the intended behavior.

See also [runtime ports](./ports.md), [replication](./replication.md), and [architecture](../architecture.md).
