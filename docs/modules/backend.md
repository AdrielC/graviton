# Backend Adapters

Backend modules translate runtime storage ports into concrete persistence systems. The supported server composition is deliberately narrower than the number of adapter types in the tree.

| Adapter | Proven operations | Known boundary |
| --- | --- | --- |
| Filesystem | CAS block and manifest persistence, native cursor inventory, metadata, manifest deletion, exact GC, quarantine, restore, and durable repair journal | Local disk or a shared filesystem whose locking and atomic-move semantics have been qualified |
| PostgreSQL | Transactional manifests, streamed object chunks, bounded KV, range tracking, and replica index | Requires the repository DDL and target-database qualification |
| S3/MinIO | Content-addressed blocks plus full-object and generic multipart object lifecycles | Requires provider-specific multipart and concurrency qualification |
| RocksDB | Scoped KV `put/get/delete`, including reopen persistence | Not wired as a blob or block store |

## Server compositions

The server builds `CasBlobStore` from one manifest repository and one block store:

```text
fs mode:      filesystem manifests + filesystem blocks
s3/minio:     PostgreSQL manifests + S3-compatible blocks
embedded API: filesystem manifests + filesystem blocks
```

The default server, embedded facade, and CLI are database-free. S3/MinIO server modes retain PostgreSQL manifests so multiple server processes can share blob metadata.

## PostgreSQL

`PgBlobManifestRepo` is the implemented manifest adapter for S3/MinIO server mode. It writes blob, block, and ordered span records transactionally, streams block references, and pages inventory with keyset SQL. `PgRepairJournal` persists shared repair progress and unresolved failures.

`PgImmutableObjectStore` and `PgMutableObjectStore` persist object metadata plus ordered chunks. Uploads use one transaction, cap each database chunk at 1 MiB, and roll back partial data on failure. `PgKeyValueStore` enforces a 32 MiB value boundary, and `PgReplicaIndex` transactionally replaces locator sets.

`PgRangeTracker` has real range-set encoding and merging, with an in-memory cache and a pluggable `KeyValueStore`. Persistence is only as strong as the provided KV implementation.

## S3 and MinIO

`S3BlockStore` is the server's implemented S3 path. It derives deterministic object keys from block content IDs and implements block writes, reads, existence checks, and duplicate outcomes.

`S3BlobStore` implements full-object upload, adaptive bounded multipart completion, server-side multipart promotion after content hashing, retrieval, stat, inventory, inspection, deletion, health checks, and explicit abort on interruption. `S3ImmutableObjectStore` and `S3MutableObjectStore` implement prefix-isolated head/list/get/put/copy/delete with adaptive bounded multipart buffering. Streaming S3 writes reserve the complete adaptive part ceiling through `TransferBudget` before accepting bytes.

## RocksDB

`RocksKeyValueStore` calls the native RocksDB driver for `put`, `get`, and `delete`. Acquisition and release are scoped, and the adapter closes both `RocksDB` and `Options`. Tests cover byte round-trips, layer wiring, and close/reopen durability.

RocksDB is not currently a CAS backend. Building one requires a `BlockStore` or `BlobManifestRepo` adapter plus the relevant integrity and concurrency tests.

## Promotion checklist

An adapter is treated as supported only after it has:

1. Real I/O for every advertised operation.
2. Explicit resource acquisition and release.
3. Integrity, restart, failure, and concurrency coverage.
4. Configuration and operational documentation.
5. An integration test for the composition used by the server.
6. The published `graviton-backend-laws` suite mounted against an isolated real instance.
