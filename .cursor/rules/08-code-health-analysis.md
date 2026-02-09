# Codebase Health Analysis & Restructuring Plan

## What's Working Well

### 1. Iron Refined Types (types.scala)
The `SizeTrait` hierarchy is excellent — it provides compile-time bounds, runtime
validation, `Integral`/`DiscreteDomain` instances, and checked arithmetic in a single
coherent abstraction. The `RefinedTypeExt` bridge to zio-schema is clean.

### 2. Chunker Architecture (graviton-streams)
Clean separation of concerns:
- `ChunkerCore` is a pure state machine (no ZIO dependency)
- `Chunker` wraps it in `ZPipeline` via `ZChannel`
- `FiberRef[Chunker]` enables per-fiber configuration
- Multiple strategies (fixed/CDC/delimiter) behind one trait

### 3. CAS Blob Store Pipeline
The `CasBlobStore.put()` is a well-structured multi-stage pipeline:
- Queue-based fan-out with proper `Take` signaling
- Scoped fiber lifecycle management
- Clean manifest construction from block batch results

### 4. Range Algebra (Span, RangeSet, RangeTree)
Lawful, well-tested data structures with proper `DiscreteDomain` abstraction.
Clean integration with refined types via `Integral[T]` instances.

### 5. Manifest Codec (FramedManifest)
Good use of scodec with proper version tagging, bounds enforcement, and
annotation codec with duplicate detection.

### 6. Scan Algebra (Scan + FreeScan)
Two complementary approaches with clean composition laws. The `StateCompose`
typeclass for automatic state merging is elegant.

---

## Areas Needing Improvement

### 1. Error Types Inconsistency
**Problem**: Core uses `Either[String, A]`, streams use sealed `ChunkerCore.Err`,
runtime uses `Throwable`. The bridging is ad-hoc.

**Resolution**:
- [x] Defined `GravitonError` sealed hierarchy in `graviton.core.GravitonError`.
- [x] `ChunkerCore.Err` now has `message` method and `toGravitonError` converter.
- [x] `StoreOps` provides opt-in typed-error extensions (`insertFileTyped`, `getTyped`, etc.).

### 2. `asInstanceOf` Overuse in Record/Scan State
**Problem**: `IngestScan`, `FS.counter`, etc. use `asInstanceOf[S]` casts after
constructing `kyo.Record` values. This is technically safe (Record erases) but
hides bugs if field names change.

**Plan**:
- [ ] Create helper constructors for common Record shapes.
- [ ] Consider using plain case classes for scan state instead of Records when the
  state is private and not part of the public API.

### 3. BlobWriteResult Duplication
**Problem**: `BlobWriteResult` was defined inline in `BinaryAttributes.scala`.

**Resolution**:
- [x] Moved to its own file `graviton.core.attributes.BlobWriteResult.scala`.
- [x] Runtime module re-exports it via `export`.

### 4. Missing Chunker Abstraction in Core
**Problem**: `AGENTS.md` lists "Introduce Chunker abstraction" as a TODO, but it already
exists in `graviton-streams`. The AGENTS.md is stale.

**Plan**:
- [x] Mark this as done in AGENTS.md (Chunker exists, configurable via FiberRef).

### 5. BinaryAttributes Validate is Trivially Total
**Problem**: `BinaryAttributes.validate` returns `Either[Nothing, BinaryAttributes]` and
always returns `Right(this)`. The advertised/confirmed split isn't enforced.

**Plan**:
- [ ] Implement real validation: check that confirmed values are consistent with
  advertised values (e.g., confirmed size matches advertised size if both present).
- [ ] Add a `BinaryAttributeKey`-keyed validation map.

### 6. Server Composition is Minimal
**Problem**: `Composition.scala` just provides a noop `BlobStore`. The real wiring
is in `Main.scala` with nested `ZIO.serviceWithZIO` calls.

**Plan**:
- [ ] Move route construction into a proper `HttpRoutes` service layer.
- [ ] Use `ZLayer.make` for the full dependency graph (currently done partially).

### 7. Missing insertFile / Whole-File Ingest Helper
**Problem**: AGENTS.md lists "Provide insertFile helper to replay leftovers until
stream exhaustion (whole-file ingest mode)" as a TODO.

**Plan**:
- [ ] Add `BlobStore.insertFile(path: Path, plan: BlobWritePlan)` convenience method
  that handles `ZStream.fromFile` + leftover replay.

### 8. Stale Markdown Noise
**Problem**: Multiple status/planning markdown files cluttered the root.

**Resolution**:
- [x] Archived 15 stale files to `docs/archive/`.
- [x] Root now has only: `README.md`, `AGENTS.md`, `BUILD_AND_TEST.md`,
  `CONTRIBUTING.md`, `LICENSE`, `ROADMAP.md`, `TODO.md`.

---

## Module Dependency Health

Current dependency graph is clean:

```
core (pure: Iron, zio-schema, scodec, kyo-data)
  ↓
streams (core + zio-streams)
  ↓
runtime (core + streams + shared-protocol + zio-nio)
  ↓
protocol/* (runtime + scalapb + zio-grpc + zio-http)
  ↓
backend/* (runtime + backend-specific SDK)
  ↓
server (runtime + all protocols + all backends)
```

**Good**: No circular dependencies. Core is effect-free. Streams adds only zio-streams.
Runtime adds ZIO services. Protocol/backend are leaf modules.

**Watch out**: `core` depends on `kyo-data` + `kyo-core` + `kyo-prelude` + `kyo-zio`.
This is a heavy dependency for a "pure core" module. Consider whether Kyo types
(Record, Tag) could be isolated to a `graviton-kyo` bridge module.
