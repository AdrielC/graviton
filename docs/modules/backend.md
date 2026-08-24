# Backend Adapters

Backend modules translate runtime storage ports into concrete persistence systems. The supported server composition is deliberately narrower than the number of adapter types in the tree.

| Adapter | Proven operations | Known boundary |
| --- | --- | --- |
| Filesystem | CAS block and manifest persistence, lookup, streaming, metadata, manifest deletion | Local disk only; no garbage collector |
| PostgreSQL | Transactional manifest persistence and ordered block references | Generic object, KV, and replica ports are still scaffolds |
| S3/MinIO | Content-addressed block `put/get/exists`, including duplicate detection | Generic object-store ports remain scaffolds |
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

`PgBlobManifestRepo` is the implemented manifest adapter for S3/MinIO server mode. It writes blob, block, and ordered span records transactionally and reconstructs manifests for reads.

The following classes currently satisfy interfaces but return empty or no-op results: `PgImmutableObjectStore`, `PgMutableObjectStore`, `PgKeyValueStore`, and `PgReplicaIndex`. Their presence should not be read as support.

`PgRangeTracker` has real range-set encoding and merging, with an in-memory cache and a pluggable `KeyValueStore`. Persistence is only as strong as the provided KV implementation.

## S3 and MinIO

`S3BlockStore` is the server's implemented S3 path. It derives deterministic object keys from block content IDs and implements block writes, reads, existence checks, and duplicate outcomes.

`S3BlobStore` implements full-object upload, multipart completion, retrieval, and deletion, but does not implement `stat`. The generic `S3ImmutableObjectStore` and `S3MutableObjectStore` remain scaffolds.

## RocksDB

`RocksKeyValueStore` calls the native RocksDB driver for `put`, `get`, and `delete`. Acquisition and release are scoped, and the adapter closes both `RocksDB` and `Options`. Tests cover byte round-trips, layer wiring, and close/reopen durability.

RocksDB is not currently a CAS backend. Building one requires a `BlockStore` or `BlobManifestRepo` adapter plus the relevant integrity and concurrency tests.

## Promotion checklist

A scaffold becomes a supported backend only after it has:

1. Real I/O for every advertised operation.
2. Explicit resource acquisition and release.
3. Integrity, restart, failure, and concurrency coverage.
4. Configuration and operational documentation.
5. An integration test for the composition used by the server.
