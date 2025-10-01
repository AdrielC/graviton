# Graviton Glossary and Terminology

This document defines the key terms used in **Graviton**, the content-addressable storage (CAS) layer for immutable binary data.  
Its goal is to provide a single source of truth for the vocabulary and invariants used throughout Graviton.

> **Note:** Higher-level concepts like files, documents, folders, and permissions live in a separate layer (e.g., Quasar) and are **explicitly out of scope**.

---

## What Graviton Is

Graviton is a **CAS layer**. Its responsibilities:

- Breaks incoming bytes into **Blocks** of bounded size.  
- Hashes and deduplicates each Block so identical content is stored only once.  
- Records how Blocks join together into a **Blob** via a **Manifest**.  
- Tracks where replicas of each Block live across backends (Stores, Sectors, Replicas).  
- Streams bytes back in order, reconstructing the original content.  

Everything else—documents, cases, workflows—lives **above** Graviton.

---

## Fundamental Types and Primitives

### Block
- Smallest immutable unit of storage.  
- Identity = `(hash, algo)` where algo = hash algorithm.  
- Hashing algorithms: **BLAKE3** (default), **SHA-256** (FIPS).  
- Immutable once written; deduplicated on identical `(hash, algo)`.  
- Boundaries determined by a **Chunker**.  

### BlockKey
- Pure CAS identifier for a Block (`hash, algo`).  
- Size is stored alongside but not part of the identity.  
- Globally unique for block content.  

### Manifest
- Ordered list of **ManifestEntry = (offset, size, block: BlockKey)**.  
- Defines how Blocks assemble into a Blob.  
- Independent from Block identity. Immutable once persisted.  

### Blob
- Contiguous immutable byte stream defined by its Manifest.  
- Identity = **BlobKey**: `(fullHash, algo, totalSize [, mediaTypeHint])`.  
- Different chunking strategies → same BlobKey for identical content.  

### BlobKey
- Identifies a complete Blob.  
- Subclasses:
  - **CasKey** = content-addressable form.  
  - **WritableKey** = user-supplied key.  

---

## Storage and Replication

### Store
- Physical backend (S3, GCS, Azure Blob, Ceph, POSIX).  
- Configurable credentials and lifecycle status.  

### Sector
- Driver-specific address within a Store (e.g., S3 key, file path, DB row ID).  
- Opaque to higher layers.  

### Replica
- Placement of a Block on a Store: `(BlockKey, StoreId, Sector, ReplicaStatus, …)`.  
- Status values:
  - `Active` (readable)  
  - `Quarantined` (temporarily excluded)  
  - `Deprecated` (eligible for GC)  
  - `Lost` (unreadable, may trigger repair)  

---

## Additional Concepts

### Offset and Size
- **Offset:** Byte position within a Blob (opaque long).  
- **Size:** Positive int/long, refined to prevent zero values.  

### Hash and Algorithm
- Cryptographic hashes identify Blocks and Blobs.  
- Algorithm is encoded in BlockKey and BlobKey.  

### Binary Attributes
- **Advertised:** Supplied by client (claimed size, MIME).  
- **Confirmed:** Computed by service (true size, hash).  
- Each entry tracks its **origin** (`client`, `server`, `build-info`).  
- Invalid attributes (e.g., wrong size) → upload rejected.  

### Chunker
- Determines Block boundaries.  
- Implementations:
  - Fixed-size  
  - Rolling hash (FastCDC)  
  - Anchored chunking (semantic markers like `/stream … endstream`)  
- Must never emit empty blocks.  
- Active Chunker stored in `FiberRef[Chunker]`.  

---

## Storage Layers

### BlockStore
- Ingest primitive.  
- Exposes `storeBlock(attrs)` sink → reads ≤ MaxBlockSize, hashes, deduplicates, emits BlockKey.  
- Returns leftovers for next block.  

### Manifest Builder
- Repeatedly runs `storeBlock`, records offsets + sizes, computes BlobKey.  
- Persists Manifest and BlobKey.  

### BlobStore
- Pluggable backend storing blocks.  
- Handles replication and healthy replica selection.  

### BlockResolver
- Maps BlockKeys to live replicas.  
- Selects healthy replica for reads.  

### BinaryStore
- Primary public API.  
- Provides streaming-friendly operations:
  - `insert` → returns BlobKey (CAS).  
  - `insertWith(key: WritableKey)` → stores under user key.  
  - `exists`, `findBinary`, `listKeys`, `copy`, `delete`.  

---

## Blob Flows

### Ingest Flow
1. Call `storeBlock(attrs)` → one-block sink.  
2. Stream through → sequence of BlockKeys.  
3. Record manifest entries.  
4. Compute full hash and size → BlobKey.  
5. Persist manifest + BlobKey.  
6. Replicate blocks per policy.  

### Read Flow
1. Load manifest from BlobKey.  
2. For each entry:  
   - Resolve healthy Replica.  
   - Read block bytes.  
   - Verify integrity (spot/full re-hash).  
3. Stream bytes in order.  
4. Random access via manifest binary search.  

---

## Frame Format
Each Block is wrapped in a **Frame** (self-describing container):

- Magic bytes + version  
- Flags + algorithm IDs (hash, compression, encryption)  
- Size fields (plaintext, compressed, ciphertext)  
- Nonce + integrity hash  
- Optional metadata (dict IDs, file IDs, chunk indices)  
- Payload = encrypted/compressed block  

---

## Excluded Concepts
Graviton **does not** manage:
- Files, folders, documents, packages  
- Permissions, access control, audit trails  
- Business rules or workflows  
- File type semantics (e.g., PDF parsing)  

These belong to the **application layer** (e.g., Quasar).
