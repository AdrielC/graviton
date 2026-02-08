# Graviton — Project Overview & Architecture

## What Is Graviton?

Graviton is a **content-addressed binary storage engine** written in Scala 3, built on
ZIO 2.x for concurrency/streaming, Iron 3.x for refined domain types, and scodec for
binary codec work. It ingests arbitrary binary blobs, chunks them into bounded blocks
(via configurable CDC/fixed/delimiter strategies), hashes them for deduplication, stores
blocks via pluggable backends (filesystem, S3/MinIO, RocksDB), and maintains manifests
that map blob-level byte ranges to ordered block references.

## Module Map

```
graviton-core        Pure domain: types, keys, manifests, ranges, scan algebra, attributes
graviton-streams     ZIO streaming layer: Chunker, HashingZ, StreamTools, scodec interop
graviton-runtime     ZIO service layer: BlockStore, BlobStore, CasBlobStore, BlobStreamer,
                     metrics, resilience, constraints, indexes, legacy adapters
protocol/            gRPC (ScalaPB + zio-grpc), HTTP (zio-http), shared models (cross-compiled JVM/JS)
backend/             graviton-s3, graviton-pg, graviton-rocks — pluggable store backends
server/              Wiring + composition, HTTP/gRPC entrypoints, Prometheus exporter
frontend/            Scala.js + Laminar dashboard
quasar-*             Metadata envelope system (core, HTTP, frontend, legacy)
core-v1              Legacy bridge using zio-prelude-experimental
dbcodegen/           Postgres schema → Scala codegen tool
```

## Technology Stack

| Concern          | Library / Version           |
|------------------|-----------------------------|
| Language         | Scala 3.8.1                 |
| Effect system    | ZIO 2.1.23                  |
| Streaming        | zio-streams (bundled w/ ZIO)|
| Schema / codecs  | zio-schema 1.7.6            |
| Refined types    | Iron 3.2.2                  |
| Binary codecs    | scodec-core 2.3.3           |
| Hashing          | blake3 3.1.2 + JDK SHA-256  |
| HTTP             | zio-http 3.7.4              |
| gRPC             | zio-grpc 0.6.3 / ScalaPB    |
| Frontend         | Laminar 17.1.0 / Scala.js   |
| Build            | sbt 1.11.5                  |
| Testing          | zio-test                    |
| Record types     | kyo-data (Record, Tag)      |

## Key Domain Concepts

- **Block**: `Chunk[Byte] :| MinLength[1] & MaxLength[16777216]` — bounded byte chunk (max 16 MiB)
- **BinaryKey**: Content-addressed key (Blob | Block | Chunk | Manifest | View), keyed by `KeyBits` (algo + digest + size)
- **Manifest**: Ordered list of `ManifestEntry`s mapping `Span[BlobOffset]` → `BinaryKey`
- **BlobLocator**: Backend-neutral `scheme://bucket/path` pointer
- **BinaryAttributes**: Advertised vs confirmed attribute records (size, mime, digests, custom)
- **Chunker**: Configurable `ZPipeline[Any, Err, Byte, Block]` (fixed / FastCDC / delimiter)
- **Scan**: Lawful stateful stream transducer (both `Scan` trait and `FreeScan` free monad)
