# Cedar | Next Gen Blob/Block Store Implementation Plan

This document captures the detailed, execution-ready plan for building the
next-generation content-addressable blob and block store for Graviton. The
objective is to unify filesystem and S3 transports under a consistent content
addressable model, enforce invariants with Iron refined types, and deliver a
manifest-driven storage format that supports reversible metadata changes and a
future append-only DAG.

## 0. Glossary

- **CAS** — Content-addressable storage where the identity of an object is the
digest of its bytes.
- **BinaryKey** — Tuple `(algo, hash, size, mime?)` that uniquely identifies a
blob by its digest, length, and optional MIME type.
- **BlobLocator** — Tuple `(scheme, bucket, path)` describing the physical root
location derived deterministically from a `BinaryKey`.
- **Transport** — Minimal object store operations (filesystem, S3, GCS, Azure).
- **Layout** — Arrangement of bytes beneath a locator (framed chunks plus
manifest, or single monolithic object).
- **BinaryAttributes** — Provenance-aware metadata captured alongside content
(size, mime, digests, history, etc.).
- **Patch Log** — RFC 6902/7386 deltas against `manifest.json` that enable
reversible metadata changes.
- **Iron** — Refined types used to enforce compile-time and runtime invariants.

## 1. Core Types & Iron Subtypes

### 1.1 Iron refinements (Scala 3, `iron`, `zio-json`)

- `Algo` — refined `String` limited to `{ "sha-256", "blake3", "md5" }` with
  extension hooks.
- `HexLower` — refined string matching `[0-9a-f]+` for lowercase hexadecimal.
- `Size` — `Long` constrained to be ≥ 0 (blobs may be empty).
- `ChunkSize` — `Int` constrained to be > 0 (chunk frames must not be empty).
- `Mime` — refined string satisfying a minimal MIME regex; practical sniffing
  verifies values.
- `ChunkIndex` — `Long` constrained to be ≥ 0.

`BinaryKey.make(algo, hash, size, mime?)` becomes the smart constructor that
validates digest lengths for the algorithm and returns either a valid
`BinaryKey` or an error.

### 1.2 Binary identity & location

```scala
final case class BinaryKey(
  algo: Algo,
  hash: HexLower,
  size: Size,
  mime: Option[Mime]
)

final case class BlobLocator(
  scheme: "file" | "s3" | ...,
  bucket: String,
  path: String
)
```

`BlobLocator` values are always derived from a `BinaryKey`; they are never
constructed manually by callers.

### 1.3 Deterministic locator strategy

A sharded locator strategy ensures consistent layout across transports:

```
<prefix>/<algo>/<ab>/<cd>/<algo>-<hash>-<size>/
```

- `ab` and `cd` capture the first two and next two hex characters of the hash,
  respectively, providing configurable fan-out.
- The same layout applies to filesystem, S3, and other transports.

## 2. Transport Abstraction (Orthogonal FS/S3)

### 2.1 Interface

```scala
trait ObjectStoreTransport {
  def head(bucket: String, path: String): IO[IOException, Option[(Long, Option[String])]]
  def list(bucket: String, prefix: String): ZStream[Any, IOException, String]
  def get(
    bucket: String,
    path: String,
    range: Option[(Long, Long)] = None
  ): ZStream[Any, IOException, Byte]
  def put(bucket: String, path: String): ZSink[Any, IOException, Byte, Nothing, Unit]
  def putMultipart(
    bucket: String,
    path: String,
    minPart: Int
  ): ZSink[Any, IOException, Byte, Nothing, Unit]
  def delete(bucket: String, path: String): IO[IOException, Unit]
  def copy(
    srcBucket: String,
    srcPath: String,
    dstBucket: String,
    dstPath: String
  ): IO[IOException, Unit]
}
```

### 2.2 Implementations

- **FileTransport** — Uses NIO APIs. `putMultipart` delegates to `put`; range
  reads use `ZStream.fromPath`.
- **S3Transport** — Backed by `zio-aws`. Supports multipart uploads with ≥ 5 MiB
  parts, `HeadObject` for stat, URL-encoded `CopyObject`, and streaming bodies
  via `ZStream`.

The transport layer encapsulates filesystem versus S3 quirks; higher layers are
fully shared.

## 3. Layout Policy

```scala
enum BlobLayout {
  case FramedManifestChunks
  case MonolithicObject
}

final case class StorePolicy(
  layout: BlobLayout,
  minPartSize: Int = 5 * 1024 * 1024
)

trait PolicyResolver {
  def policyFor(scheme: String): StorePolicy
}
```

Default policies:

- Filesystem → `FramedManifestChunks` (authoritative).
- S3 → `FramedManifestChunks` authoritative with optional monolithic materialized
  views for export.

### 3.1 Framed layout (authoritative)

Under the locator root:

- `manifest.json` — Canonical descriptor.
- `chunks/<i>` — Chunk files (non-empty).

Advantages include chunk-level dedupe, robust resume semantics, parallel I/O,
and natural materialized views.

### 3.2 Monolithic layout (export)

Under the locator root:

- `object.bin` — Single contiguous object (result of S3 multipart uploads).
- Optional `manifest.json` for parity and audit.

Best suited for HTTP range reads and external consumption.

## 4. JSON Manifest & Patch Log

### 4.1 Manifest v1 structure

```json
{
  "schema": "graviton://blob-manifest@1",
  "key": {
    "algo": "sha-256",
    "hash": "<hex>",
    "size": 12345,
    "mime": "application/pdf"
  },
  "layout": "framed | mono",
  "chunks": [
    {
      "i": 0,
      "size": 1048576,
      "digest": { "sha-256": "<hex>", "blake3": "<hex>" },
      "path": "chunks/00000000"
    }
  ],
  "attributes": { /* BinaryAttributes snapshot + attrsDigest */ },
  "meta": {
    "created": "2025-10-01T00:00:00Z",
    "generator": "Quasar/Graviton 1.0"
  }
}
```

### 4.2 Patch log (Diffson-style)

- Stored under `manifest.patches/YYYYMMDDThhmmssZ.json`.
- Each entry is an RFC 6902 JSON Patch or RFC 7386 Merge Patch applied to the
  manifest JSON.
- Rewind operations apply inverse patches in order (Diffson provides diff/apply;
  we add a ZIO JSON adapter).

Chunk files are immutable; only manifests and patch logs mutate, simplifying
audits and rollbacks.

## 5. ZIO JSON Patch (no Circe dependency)

- Implement a `given Jsony[zio.json.ast.Json]` so Diffson can operate directly
  on `zio.json.ast.Json`.
- Provide utilities:
  - `diff(a: Json, b: Json): JsonPatch[Json]`
  - `applyPatch(doc, patch): IO[PatchError, Json]`
  - `applyMergePatch(doc, merge): IO[PatchError, Json]`
- Property test that `applyPatch(a, diff(a, b)) == b` for randomly generated JSON
  documents.

## 6. BinaryAttributes (Provenance, Merge, Diff, History)

### 6.1 Tracked values and precedence lattice

- `Tracked[A](value, source, at, note?)`
- Source precedence: `Sniffed < Derived < ProvidedSystem < ProvidedUser < Verified`
- Merge rule: higher precedence wins; ties fall back to the most recent
  timestamp.

### 6.2 Structure

```scala
final case class BinaryAttributes(
  size: Option[Tracked[Size]],
  chunkCount: Option[Tracked[Long :| NonNegative]],
  mime: Option[Tracked[Mime]],
  digests: Map[Algo, Tracked[HexLower]],
  extra: Map[String, Tracked[String]],
  history: Vector[(String, Instant)]
)
```

Key APIs:

- `upsertSize`, `upsertChunkCount`, `upsertMime`, `upsertDigest`, `upsertExtra`
- `record(event)` for provenance logging
- `diff(that)` for change analysis

Attributes are persisted inside `manifest.json` (and optionally in an external
DB). `attrsDigest` locks integrity across updates.

## 7. BlobStoreBase (Shared Implementation)

### 7.1 API (CAS-first)

```scala
trait BlobStore {
  def put: ZSink[Any, IOException, Byte, Nothing, BlobWriteResult]
  def get(key: BinaryKey): ZStream[Any, IOException, Byte]
  def stat(key: BinaryKey): IO[IOException, Option[BlobStat]]
  def delete(key: BinaryKey): IO[IOException, Unit]
}

final case class BlobWriteResult(
  key: BinaryKey,
  locator: BlobLocator,
  attributes: BinaryAttributes
)

final case class BlobStat(
  keyHint: Option[BinaryKey],
  etag: Option[String],
  lastModified: Option[Instant]
)
```

Callers provide bytes only; the store derives the locator from the computed
`BinaryKey`.

### 7.2 Single-pass `put`

- Use `tapChunks` to:
  - Update parallel digests (`sha-256` primary, `blake3`, `md5`).
  - Increment size and chunk counts.
  - Sniff MIME on the first chunk (`Sniffed` provenance; can be overridden).
- Dynamically cut over to multipart sinks when the running size crosses policy
  thresholds (S3); filesystem writes remain direct.
- When the stream completes:
  - Build the `BinaryKey` (validated via Iron).
  - Derive the `BlobLocator` using the sharded strategy.
  - For framed layout, ensure chunk objects are non-empty (`ChunkSize`), write
    chunk files, and commit `manifest.json` last for atomicity.
  - For monolithic layout, write `object.bin` (empty blob produces empty file)
    and optional manifest.
  - If `BinaryAttributes.expected` is set via `FiberRef`, check CAS and fail on
    mismatches.
- Return `BlobWriteResult` containing the computed key, locator, and attributes.

### 7.3 `get`

- Resolve the locator from the `BinaryKey`.
- Framed layout — read `manifest.json`, stream `chunks[i]` sequentially, and
  optionally validate per-chunk digests.
- Monolithic layout — stream `object.bin`, with range reads supported on S3.

### 7.4 `stat`

- Prefer manifest snapshots when present.
- Fallback to `head` for size/etag.
- Merge information with persisted attributes where applicable.

### 7.5 `delete`

- Framed — delete `manifest.json`, the patch log directory, and all `chunks/*`.
- Monolithic — delete `object.bin` and optional manifest.

## 8. Append-Only Framed DAG (Phase 2)

Planned after v1 ships:

- Bootstrap frame `F0` contains ≤ 50 bytes of cleartext and an encrypted pointer
  to the first content frame.
- Content frames `CF(i)` are encrypted per index and include backward pointers
  (`prevHash`).
- Tip pointer `LF` stores the latest frame, optionally with skip links for fast
  traversal.
- Keys derive from `BinaryKey` via HKDF; possessing the key grants access to the
  DAG.
- Filesystem and S3 hold identical objects; only transport semantics differ.

## 9. Schema & Validation

- Derive JSON Schemas with `scala-jsonschema` for `BinaryKey`, `BlobLocator`,
  `BinaryAttributes`, and Manifest v1.
- Embed schema URIs in `manifest.json`.
- Validate manifests on read/write in debug/staging; enforce validation in CI.

## 10. Observability

- Prometheus metrics via `zio-metrics-connectors`:
  - `blob_bytes_written_total{scheme,layout}`
  - `blob_bytes_read_total{scheme,layout}`
  - `blob_put_seconds_bucket{scheme,layout}`
  - `blob_put_failures_total{reason}` with `cas|io|policy`
- Logging enriched with correlation IDs (MDC) and short hash prefixes.
- Emit audit events for put/get/delete, patch application, and CAS mismatches.

## 11. Security Hooks (KMS/JWT)

- KMS abstraction (encrypt/decrypt/sign/verify) with:
  - In-memory implementation for development.
  - Pluggable production KMS/HSM backends.
- JWT service abstraction for signing/verifying tokens with key IDs.
- Future `BlockStore` will layer AEAD framing and KMS keys atop BlobStore.

## 12. Testing Strategy

### 12.1 Unit tests

- Iron smart constructors for algorithms, digests, size, MIME.
- ZIO JSON patch adapter with diff/apply round-trips.
- Manifest encode/decode invariants.
- `BinaryAttributes` precedence merging and diff calculations.

### 12.2 Property tests

- `applyPatch(a, diff(a, b)) == b` for random JSON.
- CAS mismatch enforcement during `put`.
- Rejection of zero-length chunk frames.
- Shard mapping invariants from hash to locator path segments.

### 12.3 Integration tests

- Filesystem/framed end-to-end put/get/stat/delete, including crash-resume with
  manifest written last.
- S3/framed multipart uploads ≥ 5 MiB, large (> 100 MiB) objects, digest versus
  ETag consistency.
- S3/monolithic export with HTTP range read fidelity.

### 12.4 Performance & chaos

- Throughput benchmarks across chunk sizes and memory profiles.
- Fan-out level experiments versus listing performance.
- Failure injection during multipart uploads to ensure cleanup.
- Corruption scenarios: chunk tampering, patch log tampering, ensuring
  validation detects issues.

## 13. Rollout & Migration

- **Phase A** — Filesystem-only (development) to validate UX, metrics, manifests,
  and patch flows.
- **Phase B** — S3 staging with framed authoritative layout and optional
  monolithic materialization for downstream clients.
- **Phase C** — Production dual-backend rollout with organization-level policies.
- Migration utility:
  - Scan legacy files, compute `BinaryKey`, and emit framed manifests/chunks.
  - Use filesystem hardlinks to avoid copy overhead.
  - Record provenance as `ProvidedSystem` and append a `migration` history entry.

## 14. Module & Dependency Outline

- `modules/core-types` — Iron types, codecs, locator strategies, schemas.
- `modules/transport-file` — NIO transport implementation.
- `modules/transport-s3` — `zio-aws` transport implementation.
- `modules/blobstore` — Shared BlobStore logic, manifests, patch log, attributes,
  metrics.
- `modules/json-patch-zio` — Diffson adapter for `zio.json`.
- `modules/blockstore` (stub) — AEAD framing + KMS hooks (future work).
- Shared dependencies: `zio`, `zio-streams`, `zio-json`, `iron`,
  `zio-metrics-connectors`, `zio-aws-s3`, `diffson-core`, `scala-jsonschema`.

## 15. Concrete Backlog

1. **Types & Iron**
   - Implement Iron aliases and codecs.
   - Add `BinaryKey.make` with digest-length validation.
2. **Locator & Policy**
   - Build `ShardedByHashStrategy` with configurable fan-out and tests.
   - Provide `PolicyResolver` defaults (filesystem framed, S3 framed).
3. **Transports**
   - Implement `FileTransport`.
   - Implement `S3Transport` with multipart correctness.
   - Optional integration tests using temporary directories/localstack.
4. **JSON Patch (ZIO)**
   - Supply `Jsony[zio.json.ast.Json]`.
   - Implement diff/apply utilities with property tests.
5. **BlobStoreBase.put**
   - Single-pass tee, digests, chunk counting, MIME sniffing.
   - Dynamic multipart cutover.
   - Derive `BinaryKey` and locator, write framed/monolithic layouts.
   - Honor expected attributes via `FiberRef`; enforce CAS.
6. **BlobStoreBase.get/stat/delete**
   - Implement layout-aware reads, stats, and cleanup.
7. **Manifest & Patch Log**
   - JSON schema, encode/decode helpers, patch log read/write, rewind, validation.
8. **BinaryAttributes**
   - Tracked values, merge precedence, diff/history, manifest integration, `attrsDigest`.
9. **Metrics & Logging**
   - Prometheus counters/histograms and MDC correlation.
10. **Docs & Examples**
    - FS framed example, S3 multipart example, patch log rewind walkthrough.
11. **Phase 2 (post v1)**
    - Append-only DAG, KMS/AEAD integration, skip links.

## 16. Security & Compliance Notes

- Immutability: data frames never mutate; only manifests and patch logs append.
- Auditability: patch log retains a replayable history; `attrsDigest` detects
  tampering.
- Zero-trust (v2): encrypt frames with per-frame keys derived from `BinaryKey`.
- RBAC/ABAC: authorize locator access via JWT claims; attach labels in
  `attributes.extra`.

## 17. Example JSON Snippets

### 17.1 BinaryKey

```json
{
  "algo": "sha-256",
  "hash": "a1b2c3...<64 hex>...",
  "size": 1048576,
  "mime": "application/pdf"
}
```

### 17.2 Patch Log Entry (RFC 6902)

```json
[
  { "op": "replace", "path": "/attributes/mime/value", "value": "application/pdf; charset=binary" },
  { "op": "replace", "path": "/attributes/mime/source", "value": "Verified" }
]
```

## 18. Acceptance Criteria (v1)

- Filesystem and S3 implementations pass identical scenario tests.
- `put` returns identical `BinaryKey` for identical byte streams across
  transports.
- Framed manifests contain correct chunk sizes/digests; zero-length chunks are
  rejected.
- JSON Patch round-trips succeed; manifest rewinds produce bit-for-bit prior
  states.
- Metrics are exposed; schemas published; CI validates manifests in tests.

## 19. Risks & Mitigations

- Multipart complexity → encapsulate in `S3Transport`; integration tests for
  large payloads.
- Digest drift/interoperability → track multiple digests with length assertions.
- Manifest tampering → rely on patch logs and `attrsDigest`; consider signatures
  in v1.1.
- MIME accuracy → start with sniff + user overrides; plan for Apache Tika and
  verification flows in v1.1.

## 20. Roadmap

- **v1** — Types, transports, BlobStoreBase, framed/monolithic layouts, JSON
  patch support, metrics, schemas, tests.
- **v1.1** — Monolithic export tooling, MIME detection improvements, additional
  digests, manifest signing.
- **v2** — Append-only framed DAG, AEAD framing, KMS integration, skip links,
  background verifiers.
- **v2.1** — Erasure coding, deep archive tiering, lifecycle policies, integrity
  sweeps.
