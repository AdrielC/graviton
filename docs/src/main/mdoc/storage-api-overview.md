# Graviton Storage API & Frame Design Overview

This document captures the authoritative plan for keeping the current behaviour
while introducing the Domain/Core split described in the architecture guide. It
serves as a migration reference rather than a disruptive redesign.

## Objectives

- Preserve all ingestion and retrieval behaviours that exist today.
- Make invariants explicit through Iron-backed opaque types in
  `graviton-domain`.
- Provide pure, reusable algorithms in `graviton-core` so they can be tested and
  reasoned about without effects.
- Extend streaming ingestion with chunk scans, hashing utilities, and spill
  management without breaking existing flows.
- Keep the public APIs stable across gRPC and HTTP surfaces.

## Binary Key Hierarchy

`BinaryKey` is a sealed trait. The following concrete keys remain available and
retain their existing semantics:

- **`BlobKey`**: `(algo, digestHex, size)` – the size remains part of the
  identity to enforce CAS guarantees.
- **`BlockKey`**: `(algo, digestHex)` – logical CAS units referenced from
  manifests.
- **`ChunkKey`**: `(algo, digestHex)` – upload/transport segmentation for retry
  and spill logic.
- **`ManifestKey`**: `(algo, digestHex)` – optional identity for framed manifests
  so they can be cached or revalidated.
- **`ViewKey`**: includes the hash, the source key, a transform descriptor, and a
  deterministic `ListMap[String, DynamicValue]` scope. Views remain a first-class
  concept and can represent redactions, projections, or tenant-specific derived
  blobs.

All constructors validate input through Iron so invalid keys cannot be created.
No public API throws; everything returns a refined result in `IO` or
`Either`-based helpers.

## ByteRange as the Canonical Range Type

`ByteRange` lives in `graviton-domain` and is used by all read paths. It stores a
start offset (inclusive) and an end offset (exclusive) using Iron refined types
(`NonNegative` and ordering constraints). `graviton-core` provides
normalisation and arithmetic helpers used by the HTTP layer, the gRPC services,
and the runtime indexes.

## Object Store APIs

`graviton-runtime` exposes two facades that preserve current behaviour:

- **`ImmutableObjectStore`** – Provides `head`, `list`, and ranged `get`
  operations that accept optional `ByteRange` arguments.
- **`MutableObjectStore`** – Extends the immutable API with single-shot `put`,
  multipart uploads, `copy`, and `delete`. Multipart uploads surface minimum part
  sizes per backend so the coordinator can adjust chunking strategies.

S3, PostgreSQL, and RocksDB continue to implement these facades. The refactor
keeps their tuning knobs (batch sizes, retry policies, cache limits) intact.

## Streaming Pipeline

The ingestion pipeline preserves the single-pass design while exposing clearer
primitives:

1. `Chunker` (from `graviton-streams`) selects either fixed or CDC boundaries.
2. `HashingZ` wraps the pure hashers from `graviton-core`, enabling SHA-256 and
   BLAKE3 digests in one pass.
3. `ScanOps` instruments streams to count bytes, emit chunk keys, and update
   metrics.
4. Spill handling (via `SpillPolicy`, `SpillPath`, `SpillHandle`) moves buffered
   chunks to disk when memory pressure rises.
5. The multipart coordinator satisfies backend thresholds (e.g., S3's 5 MiB
   requirement) and keeps a `RangeTracker` up to date.
6. Finalisation re-frames the manifest, computes the `BlobKey`, updates the
   replica index, and returns a `BlobWriteResult`.

## Frames, Hashing, and Verification

Frames and hashing logic migrate to `graviton-core` to keep algorithms pure. The
module exposes:

- `Hasher` and `MultiHasher` for SHA-256/BLAKE3.
- `Verify` helpers to recompute digests during reads.
- Framed manifest encoders/decoders that ensure byte-for-byte compatibility with
  the existing format.

Merkle support remains optional. When introduced, it will layer on top of the
same framing primitives without altering current manifests.

## API Surfaces

The gRPC and HTTP modules depend on `graviton-runtime` and surface the same
operations as today: resumable uploads, range GETs, manifest inspection, and
metadata queries. The refactor keeps endpoint shapes unchanged while delegating
implementation details to the new module structure.

## Metrics and Observability

`graviton-runtime` defines `MetricKeys` and lightweight per-backend metrics
adapters. These integrate with the Prometheus exporter in `graviton-server`. The
plan guarantees that existing counters, timers, and histograms remain available.

## Compatibility Checklist

- [x] Hashing outputs remain identical.
- [x] CAS semantics preserved (`BlobKey` size included).
- [x] Range GET behaviour unchanged via `ByteRange`.
- [x] Chunk and block semantics maintained, enabling deduplication.
- [x] Multipart uploads finalise manifests exactly as before.
- [x] Metrics exported per backend with no renames.
- [x] Shardcake session orchestration unchanged.

These notes should be used alongside the architecture guide when executing the
refactor. They ensure the repository retains all working behaviour while gaining
clearer module boundaries.
