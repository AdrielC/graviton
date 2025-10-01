# Graviton Glossary and Terminology

This document defines the key terms used in **Graviton**, the content-addressable storage (CAS) layer that preserves immutable binary data at scale.

Its goal is to provide a single source of truth for the vocabulary, invariants, and cross-cutting guarantees that show up in API signatures, operational runbooks, and code comments.

> **Scope reminder:** Higher-level concerns—documents, folders, user permissions, business workflows—live in application layers like Quasar and are **explicitly out of scope** for this glossary.

---

## What Graviton Is

Graviton is a **content-addressable storage layer**. Its responsibilities include:

- Breaking incoming byte streams into **Blocks** of bounded size without losing ordering information.
- Hashing and deduplicating each Block so identical content is stored only once across the fleet.
- Recording how Blocks join together into a **Blob** via a durable **Manifest**.
- Tracking where replicas of each Block live across backends (Stores, Sectors, Replicas) and keeping their health updated.
- Streaming bytes back in order, reassembling the original content while verifying integrity in-flight.

Everything else—documents, cases, workflows, schema-specific parsing—lives **above** Graviton.

### Design Tenets

1. **Immutability first.** Every persisted block, manifest, and blob key is immutable. Corrections are expressed as new blobs that supersede older content.
2. **Deduplication is a safety net, not an optimization knob.** Hash collisions or chunker differences must never change visible bytes.
3. **APIs expose invariants explicitly.** Offsets, sizes, hash algorithms, and replica states are first-class citizens rather than implicit conventions.
4. **Observability is built-in.** Metrics, logging, and audit trails map directly to the concepts defined in this glossary.

---

## Fundamental Types and Primitives

### Block
- Smallest immutable unit of storage.
- Identity = `(hash, algo)` where `algo` specifies the hashing algorithm.
- Supported hashing algorithms: **BLAKE3** (default) and **SHA-256** (FIPS-friendly deployments).
- Boundaries are determined by a **Chunker** that consumes the incoming byte stream.
- Blocks are never mutated; once a hash is assigned, the bytes referenced by that hash are stable for the lifetime of the system.

**Invariants**

- A block hash must be unique to its payload. If the same `(hash, algo)` is presented with different bytes, ingestion is rejected.
- Blocks are capped by `MaxBlockSize` (configurable per deployment). Chunkers must enforce this limit.
- Empty blocks are not representable.

### BlockKey
- Pure CAS identifier for a Block: `(hash, algo)`.
- Persisted alongside `size` and `compression` metadata, but those fields are not part of the identity.
- Globally unique for a block payload regardless of which backend stores it.
- Used as the foreign key in replica catalogs and manifests.

### Manifest
- Ordered sequence of **ManifestEntry** records, each `(offset, size, block: BlockKey [, attributes])`.
- Defines how Blocks assemble into a Blob without duplicating payload bytes.
- Independent from Block identity; the same block can appear in multiple manifests and offsets.
- Immutable once persisted. New manifests are created for any change in block sequence or metadata.
- Supports sparse and random access through offset lookup tables.

**Manifest Entry Guarantees**

- `offset` values are strictly increasing and start at zero.
- `size` equals the plaintext size of the referenced block section. Partial-block references are encoded via `range` metadata instead of violating block immutability.
- Manifests keep a checksum (typically BLAKE3) across entries to guard against catalog corruption.

### Blob
- Contiguous immutable byte stream defined by its Manifest.
- Identity is expressed as a **BlobKey**: `(fullHash, algo, totalSize [, mediaTypeHint])`.
- Different chunking strategies always converge on the same BlobKey for identical content; chunk boundaries are an implementation detail.
- Blobs reference multiple replicas for resilience but appear as a single logical object to clients.

### BlobKey
- Identifies a complete Blob.
- Variants:
  - **CasKey** – canonical, content-addressable representation derived from the manifest hash.
  - **WritableKey** – user-supplied alias (e.g., a logical document identifier) that points to a CasKey.
- Includes provenance metadata such as ingestion timestamp, chunker name, and replication policy.

### Binary Attributes
- Strongly typed metadata attached to either blocks or blobs.
- **Advertised** attributes originate from clients (claimed size, MIME type, filename hints).
- **Confirmed** attributes are computed server-side (true size, verified hash, detected media type).
- Each attribute records its **origin** (`client`, `server`, `build-info`) to support audit trails and conflict resolution.
- Ingestion rejects mismatched attributes (e.g., if advertised size disagrees with the streamed bytes).

---

## Storage and Replication

### Store
- Physical backend (S3, GCS, Azure Blob, Ceph, POSIX filesystem, or future drivers).
- Configured with credentials, region/endpoint, durability class, and retention policy.
- Stores surface availability and performance metrics that feed replica placement decisions.

### Sector
- Driver-specific address within a Store (e.g., S3 object key, filesystem path, database row ID).
- Treated as an opaque token outside the driver layer.
- Supports versioning metadata (ETag, generation, last-modified) for repair workflows.

### Replica
- Placement record tying a Block to a Store: `(BlockKey, StoreId, Sector, ReplicaStatus, checksums, timestamps, …)`.
- Replica status lifecycle:
  - `Active` – readable and counted towards replication targets.
  - `Quarantined` – temporarily excluded after health probes fail or integrity checks disagree.
  - `Deprecated` – superseded due to policy changes; eligible for garbage collection.
  - `Lost` – confirmed unreadable. Repair daemons schedule re-replication from healthy sources.
- Replicas track **provenance** (which ingest job created them) and **lastVerifiedAt** timestamps.

### Replication Policies
- Configured per namespace or workload.
- Define the minimum number of `Active` replicas, geographic placement rules, and encryption requirements.
- Enforcement happens asynchronously: ingest writes to a preferred store, while background workers fan out to additional stores until policy targets are met.

---

## Supporting Concepts

### Offset and Size
- **Offset:** Byte position within a Blob. Stored as a non-negative long and validated to be monotonic in manifests.
- **Size:** Positive integer/long refined to prevent zero values. Represents plaintext sizes; compression ratios are tracked separately.
- Together they define contiguous byte ranges without copying payload data.

### Hash and Algorithm
- Cryptographic hashes identify both Blocks and entire Blobs.
- Algorithms are encoded in BlockKey and BlobKey to ensure reproducible verification.
- Hash computation happens on the streaming ingest path and is revalidated opportunistically during reads.

### Chunker
- Determines Block boundaries while streaming bytes.
- Implementations:
  - **Fixed-size** – deterministic block sizes; ideal for uniform content or constrained environments.
  - **Rolling hash (FastCDC)** – yields variable-sized blocks optimized for deduplication by aligning on content features.
  - **Anchored chunking** – respects semantic markers (e.g., PDF `stream`/`endstream`) before running CDC inside anchors.
- Must never emit empty blocks and must honor the configured `MaxBlockSize`.
- The active chunker is stored in `FiberRef[Chunker]` to support per-request overrides (e.g., forcing FIPS-approved chunking for compliance workloads).

### Encryption and Compression
- Optional per-block transformations applied before persistence.
- Declared through frame headers so readers know how to decrypt/decompress.
- Encryption keys and compression dictionaries are referenced via identifiers; actual secrets live in the surrounding platform.

---

## Storage Layers and APIs

### BlockStore
- Ingest primitive that accepts a stream of bytes and emits deduplicated BlockKeys.
- Exposes `storeBlock(attrs)` sink → reads up to `MaxBlockSize`, computes hashes, deduplicates, and emits a BlockKey plus leftover bytes for the next iteration.
- Integrates with compression, encryption, and per-tenant policies.

### Manifest Builder
- Repeatedly invokes `storeBlock`, recording offsets, sizes, and attributes per block reference.
- Computes BlobKey material (full hash, total size) as it streams blocks.
- Persists both manifest metadata and the BlobKey transactionally.
- Emits detailed ingest diagnostics (block counts, replication latencies, deduplication ratios).

### BlobStore
- Pluggable backend storing the physical blocks.
- Handles replication fan-out, retention policies, and health checks.
- Maintains background tasks for repair, rebalancing, and lifecycle transitions.

### BlockResolver
- Maps BlockKeys to live replicas across all Stores.
- Selects a healthy replica for reads based on policy (closest region, lowest latency, or compliance tier).
- Surfaces telemetry for failed attempts so operators can quarantine misbehaving stores early.

### BinaryStore
- Primary public API used by higher layers.
- Provides streaming-friendly operations:
  - `insert` → stores bytes content-addressably and returns a BlobKey.
  - `insertWith(key: WritableKey)` → stores bytes under a caller-provided alias while still producing a CasKey.
  - `exists`, `findBinary`, `listKeys`, `copy`, `delete`, and `openStream` primitives.
- Exposes hooks for observability (structured logging, metrics, audit events) keyed by the concepts in this glossary.

---

## Blob Flows

### Ingest Flow
1. **Chunking:** Bytes are streamed into the active chunker which emits block-sized chunks honoring MaxBlockSize.
2. **Block storage:** Each chunk runs through the BlockStore pipeline (hash → deduplicate → encrypt/compress → persist → emit BlockKey).
3. **Manifest assembly:** Manifest builder records `(offset, size, BlockKey, attributes)` entries and maintains rolling checksums.
4. **Blob materialization:** Full blob hash, total size, and optional media hints are computed to produce a BlobKey.
5. **Catalog persistence:** Manifest and BlobKey are stored transactionally alongside advertised/confirmed attributes.
6. **Replication fan-out:** Background workers create replicas according to policy and update ReplicaStatus as health probes confirm durability.

### Read Flow
1. **Manifest lookup:** Caller supplies a BlobKey (CasKey or alias). The manifest is resolved from the catalog.
2. **Replica resolution:** For each manifest entry the BlockResolver chooses a healthy replica satisfying policy (region affinity, encryption tier, etc.).
3. **Block retrieval:** Blocks stream back from stores. Inline verification checks frame headers, block hashes, and expected sizes.
4. **Reassembly:** Bytes are concatenated in-order, optionally decrypting/decompressing frames to return plaintext.
5. **Random access:** Manifest binary search allows slicing (e.g., `range` requests) without reading the entire blob.
6. **Repair hooks:** Failed reads mark replicas as suspect; successful reads can opportunistically refresh `lastVerifiedAt`.

---

## Frame Format

Each Block is wrapped in a **Frame**—a self-describing container that allows readers to understand how to validate, decrypt, and decompress the payload without consulting external catalogs.

Frame fields include:

- Magic bytes + version to reject incompatible readers early.
- Flags + algorithm IDs (hash, compression, encryption, padding).
- Size fields (plaintext, compressed, ciphertext) for range planning and telemetry.
- Nonce + truncated integrity hash used for authenticated encryption modes.
- Optional metadata (dictionary IDs, chunk indices, manifest hints).
- Payload containing the encrypted/compressed block data.

Frames are immutable. Any transformation (recompression, re-encryption) results in a new frame with a new block identity.

---

## Operational Considerations

- **Garbage Collection:** Deprecated replicas and unreferenced manifests are cleaned up by background jobs once retention policies expire.
- **Auditing:** Every ingest, replica repair, and deletion emits structured events referencing BlockKeys, BlobKeys, and Replica IDs from this glossary.
- **Backpressure:** Streaming APIs use bounded queues to prevent unbounded memory use while maintaining throughput.
- **Error Handling:** All integrity violations promote the affected replicas to `Quarantined` or `Lost` and record alerts for operators.

---

## Excluded Concepts

Graviton **does not** manage:

- Files, folders, documents, or packages.
- Permissions, access control, or audit trails for higher-level entities.
- Business rules or workflows for applications.
- File-type semantics (e.g., PDF parsing or media transcoding).

These concerns belong to the **application layer** (for example, Quasar) which builds on top of the primitives described here.
