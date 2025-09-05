# Graviton — Storage Concepts

Friday, September 5, 2025 – 04:46 AM UTC-6

Graviton is the **content-addressable storage (CAS)** layer. It owns **immutable bytes** and the **facts** needed to reassemble them. It does **not** own users, permissions, documents, folders, or workflows.

Graviton’s job:
- Break incoming bytes into **Blocks** (bounded size).
- Hash and store each Block **once** (dedupe).
- Record how Blocks join together into a **Blob** (a contiguous logical byte stream).
- Track **where** replicas of Blocks live across backends.
- Stream bytes out again, verifiably, in order.

Everything else lives **above** Graviton.

---

## What Graviton is

- **Block** — smallest immutable unit. Identity is its `(hash, algo)`. Size is ≤ `MaxBlockSize`.
- **Manifest** — ordered entries describing where each Block sits in a Blob: `(offset, size, BlockKey)`.
- **Blob** — a contiguous immutable byte stream, defined by its Manifest. Identity includes `(fullHash, algo, totalSize [, mediaTypeHint])`.
- **Store** — a concrete backend instance (e.g., S3 bucket + account/region, Ceph pool, POSIX root).
- **Sector** — the address inside a Store where a replica lives (key/path/oid and optional byte range).
- **Replica** — the placement record coupling `BlockKey` with `(StoreId, Sector)` and lifecycle status.

All of this is immutable identity and placement.

---

## What Graviton is not

- No Documents, Folders, Tags, Matters, Cases, or Search indices.
- No Permissions (RBAC/ABAC), Audit Trails, or Workflows.
- No filetype semantics: it does not “know” about PDFs, ZIPs, OCR, redaction, or “document types.”
- No business rules. Only physics of bytes: identity, assembly, placement.

Those belong to the higher layer (e.g., Quasar).

---

## Binary attributes (lightweight, optional)

When storing data, callers may pass `BinaryAttributes` (claimed size, content type hints, createdAt, ownerOrgId, etc.).
Graviton treats attributes as validation hints and ingest-time assertions (e.g., claimed size must match actual), not as durable user metadata.

---

## Opaque types (Scala 3 style, conceptual)

- `Offset`: byte position within the Blob where a Block begins (opaque `Long`).
- `Size`: length of a chunk or total size (opaque `Int`/`Long`).
- `Hash`: bytes + algorithm (BLAKE3 by default; SHA-256 for FIPS deployments).
- `BlockKey`: opaque wrapper over `Hash` (pure CAS identity).
- `BlobKey`: `(fullHash, algo, totalSize [, mediaTypeHint])`.
- `StoreId`, `Sector`, `ReplicaStatus`: placement primitives (opaque data; only drivers interpret `Sector`).

Rule: BlockKey knows nothing about offsets. Offset/Size are Manifest concerns, not Block identity.

---

## Ingest primitive: storeBlock returns a ZSink

Why a Sink? Because a Sink fits the streaming world perfectly:
- It consumes up to `MaxBlockSize` bytes.
- It produces exactly one `BlockKey`.
- It peels leftovers (any bytes beyond the block boundary) so you can apply it repeatedly on a larger stream.

Signature (conceptual):

```scala
trait BlockStore:
  /** Build a Sink that:
    *  - reads up to MaxBlockSize bytes
    *  - hashes & stores that block
    *  - emits one BlockKey
    *  - returns any leftover Byte as leftover
    *  - uses BinaryAttributes as ingest hints/claims
    */
  def storeBlock(attrs: BinaryAttributes)
    : ZSink[Any with Scope, StorageError, Byte, Byte, BlockKey]
```

- Input: `Byte`
- Output: `BlockKey` (one per block)
- Leftover: `Byte` (anything not consumed)
- Errors: `StorageError` (e.g., too large, backend failure)

This keeps the backend API minimal. Everything else (manifests, blob assembly) composes on top.

---

## Applying the Sink correctly (repeat over a stream)

In ZIO 2, you typically drive Sinks from a `ZStream`:

```scala
// Pseudocode: repeatedly apply the one-block sink across the stream
val in: ZStream[Any, Throwable, Byte] = /* your upload stream */

val oneBlockSink = blockStore.storeBlock(attrs)

// Pattern A (recommended): turn Sink into a Pipeline, then apply with via(...)
val pipeline = ZPipeline.fromSink(oneBlockSink)  // applies the sink repeatedly
val blockKeys: ZStream[Any with Scope, StorageError, BlockKey] =
  in.via(pipeline)
```

Note on naming: historically `transduce` was used with transducers; in ZIO 2 you’ll typically do `in.via(ZPipeline.fromSink(sink))`.
If you prefer writing `in.transduce(sink)`, add a tiny extension method that internally calls `via(ZPipeline.fromSink(sink))`.

What you get: a stream of `BlockKey` values, one per stored block, while the Sink automatically handles leftovers and back-pressure.

---

## Building the Manifest while streaming

As each `BlockKey` comes out, track its `(offset, size)` to build the Manifest:

- Keep a running `currentOffset` (starts at `0`).
- For every emitted `BlockKey`, you (the layering code) know how many bytes the block consumed; record:

```
ManifestEntry = (offset = currentOffset, size = blockSize, block = BlockKey)
currentOffset += blockSize
```

- Append entries in order; at the end you have the full Manifest.

The backend does not need offsets to define Blocks. Offsets live in the Manifest that you produce as the stream advances.

---

## Finalizing the Blob

After streaming all blocks:
1. Compute the full Blob hash while streaming (or stream again if you don’t hash inline).
2. Assemble the BlobKey = `(fullHash, algo, totalSize [, mediaTypeHint])`.
3. Persist the Manifest and link it to the BlobKey.
4. (Optional) Enforce replication policy (e.g., keep ≥ 2 Active replicas across Stores).

Now the Blob is immutable, verifiable, and fetchable.

---

## Reading a Blob

To read `BlobKey`:
- Load its Manifest.
- For each entry in order:
  - Choose a healthy Replica of the `BlockKey`.
  - Read its bytes from the selected `(StoreId, Sector)`.
  - Optionally re-hash to verify (spot-check or full).
- Stream the bytes out in order (the consumer reconstitutes the exact original content).

Random access is trivial: binary search the Manifest by `offset` and fetch only needed Blocks.

---

## Placement model (Stores, Sectors, Replicas)

- Store: a backend instance you can register (S3/GCS/Azure/Posix/Ceph/etc.). Encapsulates credentials/config and a status (`Active`, `ReadOnly`, `Draining`, `Disabled`).
- Sector: driver-opaque address within a Store (e.g., S3 key, RADOS oid, POSIX path, optional byte range).
- Replica: `(BlockKey, StoreId, Sector, ReplicaStatus, timestamps, health probes)`.
  - `Active`: eligible for reads.
  - `Quarantined`: temporarily excluded from reads.
  - `Deprecated`: not used; eligible for GC.
  - `Lost`: missing/unreadable; triggers repair if policy requires.

Invariant: Graviton chooses replicas for reads; callers do not pass Sectors when reading a Blob.

---

## Typical ingest flow (end-to-end)

1. Call `storeBlock(attrs)` to get a one-block Sink.
2. Stream your upload through `via(ZPipeline.fromSink(...))` to get a stream of `BlockKey`s.
3. Accumulate a Manifest by walking each block in order (track offset/size).
4. Compute full content hash + total size → BlobKey.
5. Persist Manifest + BlobKey; commit the Blob.
6. Replicate Blocks to satisfy policy; mark Replicas `Active`.

Done. The same flow works for multi-GB inputs because it’s streaming and bounded.

---

## Clear boundary with Quasar (upper layer)

What Quasar adds on top of Graviton:
- Documents (IDs, titles, authors, case/matter associations).
- Folders / Packages (a “file” that users see can reference multiple Blobs).
- Permissions (RBAC/ABAC/clearance levels).
- Audit (who uploaded, when accessed, workflows).
- Views/Transforms (OCR, redaction, PDF/A) → deterministic transforms producing new Blobs or virtual read-paths.
- Search/Indexing (text, embeddings, metadata queries).

Graviton stays focused on identity, assembly, and placement of immutable bytes.

---

## Naming recap (final)

- Block — CAS unit (hash + algo), ≤ MaxBlockSize.
- Manifest — ordered `(offset, size, BlockKey)` entries.
- Blob — contiguous logical bytes with identity `(fullHash, algo, size [, mediaTypeHint])`.
- Store — backend instance (S3/Ceph/Posix/etc.).
- Sector — address within a Store (key/path/oid/range).
- Replica — placement record (BlockKey + StoreId + Sector + status).

Single primitive to implement:

`storeBlock(attrs): ZSink[*, StorageError, Byte, Byte, BlockKey]`

Everything else composes from that.

---

## Notes on API style

- Use opaque types for `Offset`, `Size`, `StoreId`, etc. (Scala 3).
- Keep error algebra small (`StorageError`) and let higher layers enrich.
- Keep attributes optional and validating, not authoritative metadata.
- Hash inline where possible (single pass), but allow double-pass modes for tricky clients.
- Support FIPS by pluggable hash algorithms (encode algo into both `BlockKey` and `BlobKey`).

That’s it: small, sharp, streaming-first CAS, with clear edges.