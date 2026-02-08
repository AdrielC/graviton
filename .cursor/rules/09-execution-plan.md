# Execution Plan — Restructuring Roadmap

This is the prioritized execution plan for improving Graviton's code quality, consistency,
and adherence to the patterns documented in this rules directory.

---

## Phase 1: Foundation Cleanup (Immediate)

### 1.1 Consolidate BlobWriteResult
- **File**: `modules/graviton-core/src/main/scala/graviton/core/attributes/BinaryAttributes.scala`
- **Action**: Remove the duplicate `BlobWriteResult` case class from this file.
- **Keep**: The one in `modules/graviton-runtime/src/main/scala/graviton/runtime/model/BlobWriteResult.scala`.
- **Impact**: Eliminates ambiguity for agents and developers.

### 1.2 Update AGENTS.md
- Mark "Introduce Chunker abstraction" as done (exists in graviton-streams).
- Mark "Track ingestion context via FiberRef" as done (FiberRef[Chunker] exists).
- Update date stamps.

### 1.3 Clean Root Directory
- Move `FIM_STATE_MANAGEMENT.md`, `FINAL_STATUS.md`, `FREESCAN_V2_*.md`,
  `FRONTEND_SUMMARY.md`, `GITHUB_PAGES_*.md`, `MASTER_REFACTOR_PLAN.md`,
  `PROOF_IT_WORKS.md`, `CHANGES_SUMMARY.md`, `CROSS_COMPILATION_NOTES.md`
  to a `docs/logs/` or `docs/archive/` directory.
- Keep only: `README.md`, `AGENTS.md`, `LICENSE`, `ROADMAP.md`, `TODO.md`,
  `BUILD_AND_TEST.md` in root.

---

## Phase 2: Error Model (Short-term)

### 2.1 Define GravitonError Hierarchy
- **Where**: `modules/graviton-core/src/main/scala/graviton/core/errors.scala`
- **Shape**:
  ```scala
  sealed trait GravitonError extends Product with Serializable:
    def message: String

  object GravitonError:
    final case class ValidationError(message: String, field: Option[String] = None) extends GravitonError
    final case class CodecError(message: String, context: Option[String] = None) extends GravitonError
    final case class ConfigError(message: String) extends GravitonError
    final case class StoreError(message: String, cause: Option[Throwable] = None) extends GravitonError
    final case class ChunkerError(message: String, underlying: Option[ChunkerCore.Err] = None) extends GravitonError
  ```

### 2.2 Migrate ChunkerCore.Err
- Make `ChunkerCore.Err` extend `GravitonError` (or provide a `.toGravitonError` converter).
- Update `Chunker.toThrowable` to go through `GravitonError`.

### 2.3 Typed Error Boundaries
- Runtime services: `ZIO[R, GravitonError, A]` instead of `ZIO[R, Throwable, A]`.
- Server/protocol layer: bridge `GravitonError` → HTTP status codes / gRPC status.

---

## Phase 3: Iron Type Hardening (Short-term)

### 3.1 Audit All `applyUnsafe` Usage
- Search for `applyUnsafe` across the codebase.
- Replace with `either` + proper error handling where the input is user-supplied.
- Document remaining `applyUnsafe` calls with `// SAFETY:` comments.

### 3.2 Add Boundary Value Tests
- For every refined type in `types.scala`, ensure tests cover:
  - `Min` value
  - `Max` value
  - One below min (rejected)
  - One above max (rejected)
  - `Zero` / `One` constants

### 3.3 Evaluate Kyo Dependency in Core
- Audit which core files actually use `kyo.Record` / `kyo.Tag`.
- If usage is limited to `FreeScanV2` and `IngestScan`, consider moving those
  to a `graviton-scan` module that depends on kyo, keeping `graviton-core` lighter.

---

## Phase 4: Streaming Pipeline Improvements (Medium-term)

### 4.1 insertFile Helper
- Add to `BlobStore` or as an extension method:
  ```scala
  extension (store: BlobStore)
    def insertFile(path: Path, plan: BlobWritePlan = BlobWritePlan()): ZIO[Any, Throwable, BlobWriteResult]
  ```
- Handle leftover replay for whole-file ingest.

### 4.2 Metrics Integration in Chunker
- Add optional metrics hooks to `Chunker` for:
  - Blocks produced per second
  - Average block size
  - CDC boundary reason distribution

### 4.3 Compression Pipeline
- Implement `ZPipeline` wrappers for zstd compression/decompression.
- Integrate with `FrameSynthesis.compression` in `CasBlobStore`.

---

## Phase 5: Advanced (Post-v0.1.0)

### 5.1 Anchored Ingest Pipeline
- Transport decode → sniff → anchor tokenize → CDC → compress/encrypt → frame emit → manifest.
- Use `Scan` algebra for the tokenizer + CDC stages.

### 5.2 Self-Describing Frame Format
- Magic bytes `"QUASAR"`, algo IDs, sizes, nonce, truncated hash, key ID.
- Implement as scodec codec with strict `Take` semantics.

### 5.3 Format-Aware Views
- PDF object/page maps, ZIP central directory layered over manifest offsets.
- Implemented as `ViewTransform` + `BinaryKey.View`.

### 5.4 Deduplication Index
- Rolling-hash index for block containment queries.
- Manifest mutability for dedup updates.

---

## Verification Checklist

After each phase, verify:

```bash
# Code compiles and formats
TESTCONTAINERS=0 ./sbt scalafmtAll compile

# All tests pass
TESTCONTAINERS=0 ./sbt test

# Docs build
./sbt docs/mdoc checkDocSnippets
```
