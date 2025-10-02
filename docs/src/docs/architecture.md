# Storage Architecture

Graviton organizes immutable binary data into a layered model inspired by the
Binny `BinaryStore`:

1. **Block** – a deduplicated chunk of bytes identified by a cryptographic
   `BlockKey`. Blocks are immutable and can be stored in multiple locations,
   with each physical copy represented as a `BlockSector`.
2. **BlockStore** – a pluggable backend that persists blocks on a filesystem,
   object store, or other medium. Each store reports its health and lifecycle
   status.
3. **BlobStore** – a logical registry that hashes incoming streams, writes the
   resulting blocks to one or more `BlockStore`s, and records sectors through a
   `BlockResolver` which is consulted on reads.
4. **Blob** – a contiguous immutable byte stream, defined by a `Manifest` of ordered `(offset, size, BlockKey)` entries. Its identity (`BlobKey`) is derived from the canonical content hash and is independent of chunking.
5. **Manifest** – ordered entries describing how blocks assemble a Blob; independent from block identity.
6. **View** – a virtual transformation over a file. Views are described by a
   `ViewKey` and may be materialized or computed on demand.

All APIs use ZIO streams for I/O and expose sinks for insertion so that hashing
and chunking occur in a single pass.
