# Graviton Repository Report

**Generated**: 2026-02-08
**Branch**: `cursor/codebase-strategy-and-patterns-aea2`
**Scala**: 3.8.1 | **ZIO**: 2.1.23 | **Iron**: 3.2.2 | **sbt**: 1.11.5

---

## 1. Repository Vitals

| Metric | Value |
|--------|-------|
| Total commits | 580 |
| Contributors | 2 (Adriel Casellas: 277, Cursor Agent: 303) |
| Scala source files | 256 (212 main + 44 test) |
| Lines of Scala (main) | 17,698 |
| Lines of Scala (test) | 3,457 |
| Total tests | **191** (all passing, 0 failures) |
| Proto files | 6 |
| SQL DDL files | 3 |
| Documentation pages | 68 (.md) |
| Agent rule files | 10 (.cursor/rules/) |
| sbt modules | 18 (incl. cross-compiled JS) |

---

## 2. Module Inventory

### Core Trio (the engine)

| Module | Main LOC | Test LOC | Main Files | Test Files | Purpose |
|--------|----------|----------|------------|------------|---------|
| `graviton-core` | 5,241 | 1,049 | 60 | 19 | Pure domain: types, keys, manifests, ranges, scan algebra, attributes, error hierarchy |
| `graviton-streams` | 568 | 374 | 8 | 4 | ZIO streaming: Chunker (fixed/CDC/delimiter), HashingZ, scodec interop |
| `graviton-runtime` | 2,202 | 513 | 47 | 6 | ZIO services: BlockStore, BlobStore, CasBlobStore, BlobStreamer, metrics, resilience |

### Protocol Stack

| Module | Main LOC | Test LOC | Files | Purpose |
|--------|----------|----------|-------|---------|
| `graviton-shared` | (cross JVM/JS) | — | 4 | Shared API models (zio-schema, cross-compiled) |
| `graviton-proto` | — | — | 6 .proto | ScalaPB + zio-grpc generated stubs |
| `graviton-grpc` | ~400 | 589 | 6+4 | gRPC client/gateway (upload, catalog, admin) |
| `graviton-http` | ~200 | — | 9+0 | zio-http routes and REST API |

### Backend Implementations

| Module | Main LOC | Files | Tests | Purpose |
|--------|----------|-------|-------|---------|
| `graviton-s3` | ~400 | 8 | 0 | S3/MinIO block store |
| `graviton-pg` | 550 | 4+3 | 3 test files | Postgres blob manifest repo, schema codegen |
| `graviton-rocks` | ~200 | 3 | 0 | RocksDB block store |

### Server & Frontend

| Module | Main LOC | Files | Purpose |
|--------|----------|-------|---------|
| `graviton-server` | 162 | 9 | HTTP/gRPC entrypoint, wiring, Prometheus |
| `graviton-frontend` | 3,752 | 16 | Scala.js + Laminar dashboard |
| `quasar-*` (4 modules) | 1,107 | 20 | Metadata envelope system |

### Legacy & Support

| Module | Main LOC | Files | Purpose |
|--------|----------|-------|---------|
| `core-v1` | 1,238 | 7+4 | Legacy bridge using zio-prelude-experimental |
| `db` | 157 | 1+1 | DB cursor abstraction |
| `dbcodegen` | ~1,200 | 10+4 | Postgres DDL → Scala codegen tool |

---

## 3. Type System Health

| Metric | Count |
|--------|-------|
| Refined type definitions (types.scala) | 71 |
| Sealed trait hierarchies | 32 |
| Case classes | 274 |
| Service traits | 31 |
| Enums | 32 |
| Boundary value tests | 62 (new) |

### Refined Type Coverage

All core domain quantities flow through Iron refined types:

- **Sizes**: `BlockSize`, `FileSize`, `UploadChunkSize`, `Size`, `SizeLong` — positive, bounded
- **Indexes**: `BlockIndex`, `Offset`, `BlobOffset` — non-negative
- **Strings**: `Algo`, `HexLower`, `HexUpper`, `Identifier`, `Mime`, `KekId`, `LocatorScheme`, `LocatorBucket`, `LocatorPath`, `ViewName`, `ViewArgKey`, `ViewArgValue`, `ViewScope`, `PathSegment`, `FileSegment`, `CustomAttributeName`, `CustomAttributeValue`, `ManifestAnnotationKey`, `ManifestAnnotationValue`
- **Compound**: `Block` (Chunk[Byte] refined by length), `UploadChunk`
- **Derived**: `CompressionLevel`, `NonceLength`, `ChunkCount`

All `applyUnsafe` calls in production code are documented with `// SAFETY:` comments.

---

## 4. Streaming Architecture

| ZIO Primitive | Files Using It | Role in Graviton |
|---------------|----------------|------------------|
| `ZPipeline` | 13 | Chunker, hashing, ingest programs, scodec decoders |
| `ZStream` | 15 | Byte sources, block ref streams, blob retrieval |
| `ZSink` | 9 | BlockStore.putBlocks(), BlobStore.put(), Hasher.sink() |
| `ZChannel` | 7 | Chunker internals, Scan.toChannel, scodec decoder |
| `ZLayer` | 15 | Service wiring across all modules |
| `FiberRef` | 2 | Chunker.current (per-fiber chunker config) |

### Key Pipeline: CasBlobStore.put()

```
bytes → Queue[Take[Byte]] → ingestPipeline → Chunker.pipeline → per-block hash
  → Queue[Take[CanonicalBlock]] → BlockStore.putBlocks() → Promise[BlockBatchResult]
  → manifest construction → BlobManifestRepo.put() → BlobWriteResult
```

### Key Pipeline: BlobStreamer.streamBlob()

```
DB(block refs) → .buffer(64) → .mapZIOPar(8)(blockStore.get) → .flattenChunks → bytes
```

---

## 5. Test Coverage Analysis

```
graviton-core         60 main / 19 test  ██████░░░░░░░░░░░░░░ 32%
graviton-streams       8 main /  4 test  ██████████░░░░░░░░░░ 50%
graviton-runtime      47 main /  6 test  ██░░░░░░░░░░░░░░░░░░ 13%
protocol              20 main /  4 test  ████░░░░░░░░░░░░░░░░ 20%
backend               20 main /  0 test  ░░░░░░░░░░░░░░░░░░░░  0%
server                 9 main /  2 test  ████░░░░░░░░░░░░░░░░ 22%
pg                     4 main /  3 test  ███████████████░░░░░ 75%
frontend              16 main /  0 test  ░░░░░░░░░░░░░░░░░░░░  0%
quasar-*              20 main /  1 test  █░░░░░░░░░░░░░░░░░░░  5%
core-v1                7 main /  4 test  ███████████░░░░░░░░░ 57%
db                     1 main /  1 test  ████████████████████ 100%
```

**Test suite breakdown** (191 total):

| Suite | Tests | Module |
|-------|-------|--------|
| RefinedTypeBoundarySpec | 62 | graviton-core |
| FreeScanV2Spec | 9 | graviton-core |
| FramedManifestSpec + PagedManifest | ~10 | graviton-core |
| RangeSetSpec + RangeTreeSpec | ~12 | graviton-core |
| ScanSpec + IngestScanSpec + IngestTelemetrySpec | ~8 | graviton-core |
| ChunkerSpec + ChunkerPerfSpec | ~8 | graviton-streams |
| ZStreamDecoderSpec | ~5 | graviton-streams |
| CasBlobStore + InMemory + BlobStreamer | ~7 | graviton-runtime |
| BlockFrameCodecSpec | 2 | graviton-runtime |
| LegacyFsPathResolver | 2 | graviton-runtime |
| GravitonUploadGateway* | 8 | protocol/grpc |
| GravitonCatalogClient | 4 | protocol/grpc |
| HttpClient + LegacyRepo | 4 | protocol/http |
| FreeArrow* + HttpChunkedScan + StreamCodec | ~28 | core-v1 |
| Pg repos (Block, Blob, Store) | ~6 | pg |
| Server integration (skipped) | 2 | server |

### Critical Gaps

- **backend/** (S3, RocksDB): **Zero tests**. S3 store and Rocks store have no unit tests.
- **frontend/**: **Zero tests**. 3,752 lines of Scala.js with no test coverage.
- **quasar-***: 1,107 lines across 4 modules with only 1 test file.
- **graviton-runtime**: Only 13% file coverage — constraints, indexes, kv, legacy, metrics, ops, policy, resilience all untested.

---

## 6. Dependency Health

### Core Dependencies (versions)

| Dependency | Version | Latest Known | Status |
|------------|---------|-------------|--------|
| Scala | 3.8.1 | 3.8.1 | Current |
| ZIO | 2.1.23 | ~2.1.x | Current |
| zio-schema | 1.7.6 | ~1.7.x | Current |
| zio-http | 3.7.4 | ~3.7.x | Current |
| Iron | 3.2.2 | ~3.x | Current |
| scodec-core | 2.3.3 | 2.3.x | Current |
| blake3 | 3.1.2 | 3.x | Current |
| kyo | 1.0-RC1 | 1.0-RC1 | **Pre-release; multi-field Record broken on 3.8** |

### Dependency Concerns

1. **Kyo 1.0-RC1**: Pre-release dependency in `graviton-core`. Multi-field `kyo.Record` with `&` intersection types fails at runtime on Scala 3.8. Workaround applied (case classes for `buildManifest`/`fixedChunker`). Single-field Records still work. **Risk**: Future Kyo API changes could break more code.

2. **Heavy core module**: `graviton-core` depends on `kyo-data` + `kyo-core` + `kyo-prelude` + `kyo-zio` (4 Kyo artifacts) plus `hearth` for its "pure domain" module. Consider isolating Kyo-dependent scan code.

3. **Embedded Postgres for tests**: `io.zonky.test:embedded-postgres:2.0.4` only in server test scope — good isolation.

---

## 7. Architecture Assessment

### Strengths

| Area | Rating | Notes |
|------|--------|-------|
| Type safety | ★★★★★ | Comprehensive Iron types, `SizeTrait` hierarchy, collection-level refinement |
| Streaming design | ★★★★☆ | Clean ZPipeline/ZChannel layering, proper backpressure, bounded queues |
| Separation of concerns | ★★★★☆ | Pure core → streams → runtime → protocol → server layering |
| CAS pipeline | ★★★★☆ | Well-structured multi-stage ingest with metrics hooks |
| Scan algebra | ★★★★☆ | Two complementary approaches (Scan + FreeScan) with composition laws |
| Manifest codec | ★★★★☆ | Versioned scodec with bounds enforcement |
| Range algebra | ★★★★★ | Lawful Span/RangeSet/RangeTree with DiscreteDomain |
| Error model | ★★★☆☆ | GravitonError hierarchy is new; adoption still partial |
| Documentation | ★★★★☆ | 68 doc pages, VitePress site, inline Scaladoc, agent rules |

### Weaknesses

| Area | Rating | Notes |
|------|--------|-------|
| Test coverage | ★★☆☆☆ | 0% for backend, frontend, quasar; 13% for runtime |
| Server wiring | ★★☆☆☆ | Minimal composition; nested serviceWithZIO instead of ZLayer.make |
| CI pipeline | ★★★☆☆ | Exists but no automated test-on-PR evidence in logs |
| BinaryAttributes.validate | ★★☆☆☆ | Trivially total (always Right); no real validation |
| Configuration | ★★☆☆☆ | Env var parsing in Main.scala; no structured config |
| Observability | ★★★☆☆ | Metrics infra exists but no distributed tracing |

---

## 8. Recent Session Work (This Branch)

### Commits on this branch (9 commits)

1. **Add comprehensive .cursor/rules** — 10 rule files covering architecture, style, Iron, ZIO streaming, services, scans, testing, health analysis, execution plan, code patterns
2. **Upgrade Scala 3.7.4 → 3.8.1** — Version bump + fix deprecated `-Xfatal-warnings`, fix unused implicit detection
3. **Fix Scala 3.8.1 compatibility** — Replace broken kyo.Record multi-field state with case classes, fix test call sites
4. **Fix line-merging + scalafmt** — Post-replace formatting cleanup
5. **Update rules for Scala 3.8.1** — Add kyo.Record compat warning to scan algebra rules
6. **Phase 1: Foundation cleanup** — BlobWriteResult to own file, archive 15 stale markdown files
7. **Phase 2: Unified error model** — GravitonError hierarchy, ChunkerCore.Err integration, StoreOps extensions with insertFile
8. **Phase 3: Iron type hardening** — SAFETY comments on all applyUnsafe, 62 boundary value tests
9. **Update health analysis** — Mark completed items in analysis doc

### Net impact

- **+1,338 lines** of production code (error model, store ops, BlobWriteResult extraction)
- **+143 lines** of tests (62 boundary value tests)
- **+1,138 lines** of agent rules and documentation
- **15 files** archived from root to `docs/archive/`
- **Scala 3.8.1** upgrade with full compatibility

---

## 9. Recommended Next Steps

### Immediate (blocks v0.1.0)

1. **Add backend tests** — S3BlockStore and RocksBlockStore have zero tests. At minimum add in-memory roundtrip tests using the same patterns as `InMemoryBlockStore`.
2. **Add runtime tests** — CircuitBreaker, Bulkhead, MetricsRegistry, BlobLayout, StorePolicy all untested.
3. **Implement real BinaryAttributes.validate** — Currently always returns `Right(this)`.
4. **Structured configuration** — Replace env var parsing with a proper config case class + `ZLayer.fromConfig`.

### Short-term

5. **Evaluate Kyo dependency** — The Record runtime breakage on Scala 3.8 is concerning. Audit all 13 files using `kyo.*` in core; consider isolating to a `graviton-scan` module.
6. **Compression pipeline** — Wire `FrameSynthesis.compression` to actual zstd ZPipeline.
7. **Integration test harness** — TestContainers-based tests for S3+PG roundtrip.
8. **Frontend tests** — 3,752 lines of Scala.js with zero tests.

### Medium-term

9. **Anchored ingest pipeline** — The scan algebra is ready; build the transport decode → CDC → frame emit pipeline.
10. **Self-describing frame format** — scodec codec with `"QUASAR"` magic bytes.
11. **Cold-storage tiering** — StorePolicy already modeled; implement the actual tiering logic.
12. **Deduplication index** — Rolling-hash containment queries using the existing RangeTree infrastructure.

---

## 10. File Tree (Key Paths)

```
.cursor/rules/           10 agent rule files (style, patterns, execution plan)
modules/
  graviton-core/         Pure domain: types, keys, manifests, ranges, scan, attributes, errors
  graviton-streams/      ZIO streaming: Chunker, HashingZ, scodec interop
  graviton-runtime/      ZIO services: stores, metrics, resilience, streaming, StoreOps
  protocol/
    graviton-shared/     Cross-compiled JVM/JS API models
    graviton-proto/      .proto files + ScalaPB codegen
    graviton-grpc/       gRPC client/server
    graviton-http/       zio-http REST API
  backend/
    graviton-s3/         S3/MinIO block store
    graviton-pg/         Postgres manifest repo
    graviton-rocks/      RocksDB block store
  server/graviton-server/ Wiring, HTTP/gRPC entry, Prometheus
  frontend/              Scala.js + Laminar dashboard
  quasar-*/              Metadata envelope system (4 modules)
  core/                  Legacy v1 bridge
  db/                    DB cursor abstraction
  pg/                    Postgres schema + generated bindings
dbcodegen/               DDL → Scala codegen tool
docs/                    68 documentation pages (VitePress)
docs/archive/            15 archived planning files
```
