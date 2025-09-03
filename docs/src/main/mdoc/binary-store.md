# Binary Store Design

Torrent (implemented in this repository as **Graviton**) provides an immutable
binary substrate for Quasar.  The system is organised into a small set of
layered concepts that keep storage concerns isolated from higher‑level document
workflows.

## Goals

- **Content‑addressable**: all bytes are identified by cryptographic hashes.
- **Immutable**: data never changes once written; new content yields new keys.
- **Deduplicated**: identical blocks are stored once and referenced many times.
- **Reversible transforms**: encryption and compression are recorded so reads
  can invert them transparently.
- **Location aware**: blocks may exist in multiple `BlobStore`s and are resolved
  at read time.

## Concepts

### Block
A block is a deduplicated chunk of bytes addressed by a `BlockKey`.  Blocks are
immutable and can be replicated across many stores.

### BlockSector
Each physical copy of a block is tracked as a *sector* containing the
`blobStoreId`, lifecycle `status`, and optional transform metadata.  Sectors are
aggregated by a `BlockResolver` which performs read‑time fan‑out and healing.

### BlobStore
A pluggable backend capable of reading and writing raw blocks.  Filesystem and
MinIO implementations live in dedicated modules. Support for AWS S3 is planned
but not yet available.

### BlockStore
A logical registry that hashes incoming streams, deduplicates blocks, and
forwards them to `BlobStore`s.  Each write records a `BlockSector` so that the
`BlockResolver` can track every physical copy.  Reads consult the resolver and
fan‑out across sectors, allowing blocks to exist in multiple locations.  The
store exposes sinks for streaming writes and streams for listings.

### File
A file is an ordered list of `BlockKey`s.  Its identity, the `FileKey`, is based
solely on the decoded content hash and size, making it independent of the
chunking strategy.

### FileStore
Responsible for assembling files from blocks, verifying provided metadata, and
returning manifests.  Insertions stream through chunking, hashing and block
writes in a single pass.

### View
A deterministic transformation over a file.  Views are identified by a
`ViewKey` consisting of the base `FileKey` and a chain of `FileTransformOp`s.
They may be materialised into files or rendered on demand.

## Reading data

1. Resolve the desired block or file key.
2. Locate one or more `BlockSector`s via the resolver.
3. Stream bytes from a `BlobStore`, reversing any recorded transforms.
4. Assemble blocks back into the original file representation.

## Module Layout

```
modules/core    – base types and in‑memory stores
modules/fs      – filesystem backed blob store
modules/minio   – S3‑compatible blob store via zio‑aws
modules/tika    – media type detection utilities
```

Each module provides a `ZLayer` for easy wiring into ZIO applications. An AWS
S3 module is planned; in the meantime, use the `minio` module with an
S3‑compatible endpoint.

