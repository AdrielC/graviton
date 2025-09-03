# Storage Architecture

Graviton organizes immutable binary data into a layered model inspired by the
Binny `BinaryStore`:

1. **Block** – a deduplicated chunk of bytes identified by a cryptographic
   `BlockKey`. Blocks are immutable and can be stored in multiple locations.
2. **BlobStore** – a pluggable backend that persists blocks on a filesystem,
   object store, or other medium. Each store reports its health and lifecycle
   status.
3. **BlockStore** – a logical registry that hashes incoming streams, writes the
   resulting blocks to one or more `BlobStore`s, and looks them up by key.
4. **File** – an ordered sequence of block keys. A file's identity (`FileKey`)
   is derived from the canonical content hash and is independent of chunking.
5. **FileStore** – assembles files from blocks, records manifests, and provides
   streaming read access.
6. **View** – a virtual transformation over a file. Views are described by a
   `ViewKey` and may be materialized or computed on demand.

All APIs use ZIO streams for I/O and expose sinks for insertion so that hashing
and chunking occur in a single pass.
