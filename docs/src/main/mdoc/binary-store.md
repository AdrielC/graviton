# Cedar 2.0 Binary Store Refactor

This document records the agreed plan for evolving the Graviton (Cedar 2.0) storage
stack. The objective is to provide a unified, type-safe API for storing binary
streams while preserving rich metadata and enabling both deduplicated and direct
file ingestion paths.

## 1. Refined Types and Invariants

- Adopt [Iron](https://github.com/IronCoreLabs/iron) refined types to reject
  illegal states at compile time:
  - `Size = Int :| Positive` for file lengths (`> 0`).
  - `Index = Long :| NonNegative` for block indices and offsets (`>= 0`).
  - `Block = Chunk[Byte] :| (Positive & SizeLessEqual[MAX_BLOCK_SIZE_IN_BYTES])`
    so a block can never be empty or exceed the configured maximum.
- Model metadata with `BinaryAttributeKey[A]` and store attributes in two
  `ListMap`s inside `BinaryAttributes`:
  - `advertised`: values supplied by callers (claimed size, provided hash,
    user-supplied content type, etc.).
  - `confirmed`: values derived by the service after ingestion (computed size,
    canonical hash, detected content type, and so on).
- Persist each entry as `BinaryAttribute(value, source)` so that the origin is
  tracked (e.g. `"client"`, `"server"`, `"build-info"`).
- Call `BinaryAttributes.validate` before accepting a request to enforce naming
  and MIME-type constraints.

## 2. Chunking via `Chunker`

- Replace ad-hoc chunking with a `Chunker` abstraction:

  ```scala
  trait Chunker {
    def name: String
    def pipeline: ZPipeline[Any, Throwable, Byte, Block]
  }
  ```

- The pipeline must never emit an empty chunk. Implementations include fixed
  size splitting, FastCDC, or anchored CDC that aligns with semantic markers
  (e.g. PDF `stream`/`endstream`).
- Keep the active `Chunker` in a `FiberRef[Chunker]` so callers can override it
  locally without changing method signatures.

## 3. Unified `BinaryStore` API

The core API stays centred on the **single-sink** operation. Both
`BinaryStore.insert` and `BinaryStore.insertWith` accept a byte stream, write just
enough data to materialise a `BinaryKey`, and then return that key together with
any leftover `Bytes`. Callers can inspect the leftovers to decide whether to
retry, split the upload, or surface an error. This keeps the primitive
signature aligned with Cedar’s CAS semantics while supporting user-provided
keys.

On top of that primitive we expose a **manifest-building sink** (tentatively
named `insertFile`). It repeatedly feeds the leftover bytes back into the single
sink until the input stream is fully consumed. Only when there are no leftovers
does it succeed, returning the ordered list of `BinaryKey`s that make up the
logical file. From the caller’s perspective this is the ergonomic “upload a
whole file” operation, while internally it still reuses the chunk-based
pipeline.

The block-oriented machinery underneath:

- Hashes each emitted block, checks for existing content, and persists new
  blocks through the configured `BlobStore`.
- Generates a `BinaryKey.CasKey` from the ordered block hashes plus total size.
- Maintains reference counts and sector placement as blocks are reused.

A direct ingestion mode (“**FileStore**”) keeps the high-level semantics but may
persist data in a single object (e.g. temporary file promoted after hashing)
rather than individual blocks. The implementation choice remains invisible to
callers—they always interact through `insert`/`insertWith` or the
`insertFile` wrapper. Mode selection can come from a `FiberRef[StoreMode]` that
defaults based on size thresholds (e.g. files smaller than 1 MiB choose the
file-store path).

## 4. Attribute Handling

- Accept `BinaryAttributes` from callers via a `FiberRef.currentAttributes`.
- Merge confirmed attributes (computed hash, byte size, timestamps) with
  advertised entries using the existing `++` semantics while preserving sources.
- Store the merged attributes alongside the manifest so both advertised and
  confirmed values can be retrieved later.

## 5. Environment and API Surface

- Migrate to ZIO 2.x idioms: eliminate `Has[_]`, use `ZLayer`/`ZEnvironment`,
  and capture contextual overrides with `FiberRef`s (chunker, attributes, store
  mode).
- Keep security concerns (KMS, auth, signed URLs) at the boundary APIs; the
  storage layer focuses on correctness and data integrity.
- Implement REST endpoints with ZIO HTTP’s Endpoints DSL, validate inputs with
  Iron, and return streaming downloads via `ZStream`.

## 6. Next Steps

1. Implement the `FileStore` ingestion mode and surface the manifest-building
   sink.
2. Finish all chunker implementations (fixed, FastCDC, anchored CDC).
3. Add attribute retrieval and update methods on `BinaryStore`.
4. Wire up ZIO HTTP endpoints with schema derivation and validation.
5. Expand the documentation with end-to-end examples and update `AGENTS.md`
   whenever scope or conventions change.
6. Cover both ingestion modes and attribute validation with comprehensive tests.
