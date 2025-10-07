# Storage Architecture

Graviton organises immutable binary data around a **Domain/Core** split so that
pure models remain isolated from effectful code. The structure keeps existing
behaviour while making invariants explicit and type-safe.

## Module Overview

- **`graviton-domain`** – Pure data definitions: opaque types validated with
  Iron, sealed key hierarchies, manifest schemas, policy definitions, range
  models, and error ADTs.
- **`graviton-core`** – Pure logic built on top of the domain types: hashing,
  manifest framing, range algebra, locator strategies, and other
  side-effect-free algorithms.
- **`graviton-streams`** – ZIO Streams utilities that adapt the core hashing and
  chunking logic to streaming workflows, including content-defined chunking via
  the `zio-blocks` submodule.
- **`graviton-runtime`** – Effectful services, store facades, key-value
  backends, spill management, and metrics wiring.
- **Protocol modules (`graviton-proto`, `graviton-grpc`, `graviton-http`)** –
  Public APIs exposed over gRPC and HTTP.
- **Backend modules (`graviton-s3`, `graviton-pg`, `graviton-rocks`)** –
  Production-ready storage adapters with dedicated metrics surfaces.
- **`graviton-server`** – Application wiring, configuration, Shardcake session
  orchestration, and Prometheus exporters.

This layout preserves the existing functionality while making it easier to
reason about invariants: the domain module shows the canonical shapes, the core
module contains deterministic logic, and the runtime module captures everything
that requires effects or resources.

## Binary Keys and Identity

All stored entities share the sealed trait `BinaryKey` defined inside
`graviton-domain`. Each subtype has specific identity rules:

- **`BlobKey`** – `(hashingAlgorithm, digestHex, size)` where the size is part of
  the identity to guarantee content-addressable semantics.
- **`BlockKey`** – `(hashingAlgorithm, digestHex)` describing logical CAS blocks
  referenced by manifests.
- **`ChunkKey`** – `(hashingAlgorithm, digestHex)` for transport chunks emitted by
  the upload pipeline. Chunk boundaries can differ from block boundaries while
  keeping deduplication deterministic.
- **`ManifestKey`** – Hash of the framed manifest representation, enabling
  optional caching and verification of manifest payloads.
- **`ViewKey`** – `(hashingAlgorithm, digestHex, source, transform, scope)` where
  `source` is another `BinaryKey`, `transform` describes the derivation, and
  `scope` is an ordered `ListMap[String, DynamicValue]` capturing tenant or
  derivation metadata.

The domain module exposes only validated constructors so callers cannot create
invalid keys. All constructors rely on Iron refined types and `refineEither`
instead of throwing exceptions.

## Byte Ranges and Range Algebra

`ByteRange(start, endExclusive)` lives in `graviton-domain` as the canonical
range type for ranged GET requests. The core module implements the range
algebra—normalisation, intersection, subtraction, and merge operations—so that
both read paths and index tracking share the same logic.

## Streaming Ingest Pipeline

1. **Chunk detection** – `graviton-streams` provides a `Chunker` abstraction that
   supports fixed-size or content-defined chunking through `zio-blocks`.
2. **Hashing** – `HashingZ` bridges ZIO Streams with the pure hashers from the
   core module, enabling multi-hash computations in a single pass.
3. **Spill management** – `graviton-runtime` introduces `SpillPolicy`,
   `SpillPath`, and `SpillHandle` to move in-flight data between memory and disk
   when large uploads occur.
4. **Multipart uploads** – Chunked data feeds backend multipart uploads while
   tracking coverage through `RangeTracker` and `ReplicaIndex` services.
5. **Manifest construction** – After all chunks are acknowledged, the core module
   derives the block list, frames the manifest, and computes the `BlobKey`,
   optionally emitting a `ManifestKey`.

This pipeline retains behaviour from the existing repository while making each
concern explicit and testable.

## Object Store Facades

`graviton-runtime` exposes two facades:

- **`ImmutableObjectStore`** – Listing, metadata, and ranged reads via
  `ByteRange`.
- **`MutableObjectStore`** – Extends the immutable API with multipart writes,
  copy, and delete operations.

S3, PostgreSQL, and RocksDB implementations target these facades. Feature parity
is preserved, including existing configuration knobs and metrics.

## Metrics and Observability

`MetricKeys` in the runtime module standardise counter and histogram names.
Every backend exports a dedicated metrics adapter that registers with the
Prometheus exporter in `graviton-server`. The design keeps the current metric
coverage while making it easier to add per-backend instrumentation.

## Shardcake Orchestration

Shardcake sessions coordinate multipart uploads. `SessionEntity` retains hasher
state, spill handles, and range tracking, while `MultipartCoordinator` ensures
backend-specific minimum part sizes are satisfied before finalising manifests.
These components live in `graviton-server` alongside shard key derivation and
composition wiring.

## Preservation Goals

The refactor plan is intentionally non-destructive:

- Preserve hashing behaviour for SHA-256 and BLAKE3.
- Keep content-addressable semantics where `BlobKey` includes the byte size.
- Maintain range GET behaviour through `ByteRange`.
- Keep RocksDB, PostgreSQL, and S3 backends functional with their metrics.
- Retain Shardcake coordination semantics and ensure all constructors use Iron
  instead of exceptions.

By separating models, pure logic, and runtime code, Graviton can evolve faster
without sacrificing the proven ingestion and retrieval flows already in place.
