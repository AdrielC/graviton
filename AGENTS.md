# AGENTS — Refined TODO (Graviton-focused, no BinaryStore)

## Documentation

**Core for v0.1.0**
- [x] Replace lingering *Torrent* references with Graviton-centric language. (done 2025-12-27)
- [x] Write a **binary-streaming guide** (Blocks, Blobs, Manifests, Attributes) and link it from *Getting Started*. (done 2025-12-27)
- [x] Add **filesystem + S3 backend configuration guides** (credentials, paths, env vars, buckets). (done in 8d3e829 2025-10-01)
- [x] Expand Prometheus metrics docs with a worked example (`MetricsBlobStore`, publisher/updater, scrape config). (done in 8d3e829 2025-10-01)
- [x] Document logging: correlation-ID propagation, SLF4J backend setup. (done in 8d3e829 2025-10-01)
- [x] Flesh out CLI usage pages: install, options, sample commands and responses. (done 2025-12-27)
- [x] Introduce a CONTRIBUTING guide (coding style, test commands, doc build). (done in 65b8e02 2025-11-24)

**Follow-ups**
- [ ] Add Apache Tika module page under *Modules*.
- [ ] Port performance notes + API reference from Torrent.
- [ ] Ensure `./sbt docs/mdoc test` passes after each doc change; fix nav links.
- [ ] Document replication model (Stores, Sectors, Replicas) with diagrams.
- [ ] Document manifest format and forward-compatibility guarantees.

---

## Implementation / Testing

**Core for v0.1.0**
- [ ] Finalize `BlockStore` and `BlobStore` APIs.
- [ ] Stabilize filesystem and S3 implementations.
- [ ] Ship a CLI with end-to-end ingest + retrieval tests.
- [ ] Add configuration-driven integration tests (TestContainers for backends).
- [ ] Set up CI: run tests, publish docs, push to Maven Central.

**Refactor / Storage API**
- [x] Adopt Iron refined types for sizes/indices. (done — `types.scala` with `SizeTrait`, `RefinedTypeExt`)
- [x] Split `BinaryAttributes` into advertised/confirmed keyed by `BinaryAttributeKey`; enforce `validate` at ingest. (done — `BinaryAttributes` has advertised/confirmed with `BinaryAttributeKey`)
- [x] Introduce `Chunker` abstraction (`ZPipeline[Any, Throwable, Byte, Block]`), configurable via `FiberRef`. (done — `graviton.streams.Chunker` with `FiberRef[Chunker]`)
- [ ] Provide `insertFile` helper to replay leftovers until stream exhaustion (whole-file ingest mode).
- [x] Track ingestion context via `FiberRef` (chunker, attributes, store mode). (done — `Chunker.current: FiberRef[Chunker]`)
- [ ] Persist advertised/confirmed attributes with manifests.

---

## Advanced Enhancements (post-v0.1.0)

- [ ] Implement anchored ingest pipeline (transport decode → sniff → anchor tokenize → CDC → compress/encrypt → frame emit → manifest).
- [ ] Build tokenizer + CDC pipeline (DFA/Aho-Corasick, `ZSink.foldWeightedDecompose`, 1 MiB rechunker via `ZPipeline.anchoredCdc`).
- [ ] Define + implement **self-describing frame format** (magic `"QUASAR"`, algo IDs, sizes, nonce, truncated hash, key ID, AAD encoding, strict `Take` semantics).
- [ ] Extend deduplication: store CDC base blocks, keep manifests mutable, prototype rolling-hash index for containment.
- [ ] Add format-aware views (PDF object/page maps, ZIP central directory) layered over manifest offsets.
- [ ] Add operational guardrails: bounded `Queue[Take]`, guaranteed `OutputStream` closure, ingestion bomb protection, enforced pre-commit checks.
- [ ] Add repair jobs for missing or quarantined replicas.
- [ ] Implement cold-storage tiering (configurable store policies for “active” vs “archival”).
- [ ] Add background compaction (repack small blocks, drop deprecated replicas).
- [ ] Expose metrics for deduplication ratio, replication health, and ingest latency.

---

## Coding Standards & Agent Rules

Comprehensive coding standards, architectural patterns, and restructuring plans are
maintained in **`.cursor/rules/`**:

| File | Contents |
|------|----------|
| `01-project-overview.md` | Architecture, module map, tech stack, domain concepts |
| `02-scala-style.md` | Scala 3 style guide, formatting, naming, error handling |
| `03-iron-refined-types.md` | Iron patterns: `RefinedTypeExt`, `SizeTrait`, boundaries |
| `04-zio-streaming.md` | ZIO Streams patterns: Chunker, ZChannel, Sinks, queues |
| `05-service-architecture.md` | ZLayer patterns, CAS architecture, error strategy |
| `06-scan-algebra.md` | Scan trait, FreeScan, combinators, IngestScan |
| `07-testing-standards.md` | zio-test patterns, what to test, test naming |
| `08-code-health-analysis.md` | What's working, what needs improvement |
| `09-execution-plan.md` | Phased restructuring roadmap with verification steps |
| `10-code-patterns-reference.md` | Copy-paste-ready code patterns for common tasks |

Agents **must** consult these rules before making changes.

---

## Workflow Requirements
- Always run:
  ```bash
  TESTCONTAINERS=0 ./sbt scalafmtAll test
  ```
- Keep docs healthy:
  ```bash
  ./sbt docs/mdoc checkDocSnippets
  ```
- Schema changes **must** be reflected in generated bindings:
  1. Start a local PostgreSQL instance without Docker (e.g. `apt-get install postgresql` then `pg_ctlcluster 18 main start`).
  2. Apply `modules/pg/ddl.sql` to an empty database (for example `psql -d graviton -f modules/pg/ddl.sql`).
  3. Regenerate bindings with `PG_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/graviton PG_USERNAME=postgres PG_PASSWORD=postgres ./sbt "dbcodegen/run"`.
  4. Commit the updated files in `modules/pg/src/main/resources/generated/` together with the DDL change.
- Sync with latest main before commits (git fetch origin main && git merge origin/main or rebase).
- Mark tasks as done with commit/date in this file.
