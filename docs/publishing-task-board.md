# Publishing Task Board (Actionable)

This board breaks the publishing readiness goals into small, grab-and-go tasks. Each task card specifies scope, key changes, and acceptance checks so individual agents can pick one up, implement, and commit.

## How to work these tasks
- Pick a single task card and implement only that scope per PR to keep reviews focused.
- Keep schemas and generated bindings in sync if you touch `modules/pg/ddl.sql`.
- Run `TESTCONTAINERS=0 ./sbt scalafmtAll test` before committing unless the task card explicitly calls for a broader matrix.

## Task cards

### 1) Finalize `BlockStore`/`BlobStore` API surface
- **Scope:** Close on method signatures, refined types, and immutable vs mutable store semantics.
- **Key changes:** Adopt Iron refined types for sizes/indices; split advertised vs confirmed attributes; ensure `Chunker` abstraction is wired.
- **Acceptance:** Interfaces are stable in `modules/core` and `modules/store`; downstream impls compile without TODOs around these decisions.

### 2) Filesystem backend conformance sweep
- **Scope:** Align filesystem store with the finalized APIs and refined types.
- **Key changes:** Replace ad-hoc size/index handling with refined types; ensure error paths return `Either`/`ZIO` (no thrown exceptions).
- **Acceptance:** End-to-end ingest/retrieve flows pass for filesystem backend; added/updated tests cover error cases and happy paths.

### 3) S3 backend stabilization
- **Scope:** Bring S3 implementation to parity with finalized APIs and refined types.
- **Key changes:** Normalize attribute handling, chunk sizing, retry/backoff; ensure manifests and attributes persist consistently.
- **Acceptance:** Integration test hitting a real/LocalStack S3 passes; logging/metrics capture request IDs and latencies.

### 4) CLI ingest/retrieve E2E
- **Scope:** Ship CLI end-to-end coverage for ingest + retrieve wiring through real backends.
- **Key changes:** Add scripted tests (e.g., using temporary dirs or TestContainers) that run `graviton-cli` commands; verify stored content matches source.
- **Acceptance:** CI-visible test demonstrates ingest then retrieve round-trip via CLI for filesystem backend.

### 5) Binary streaming guide
- **Scope:** Write and link a guide explaining blocks, blobs, manifests, attributes, and chunking strategy.
- **Key changes:** New doc under `docs/` with diagrams/flows; update Getting Started/nav to link it; ensure terminology stays strictly Graviton-specific.
- **Acceptance:** `mdoc` build passes; guide is discoverable from Getting Started; terminology is Graviton-specific throughout.

### 6) CLI usage docs
- **Scope:** Flesh out install + command usage pages.
- **Key changes:** Add install prerequisites, command reference, sample inputs/outputs, and troubleshooting to the CLI docs section.
- **Acceptance:** CLI docs link from README/Getting Started; examples run as written (tested manually or via doctest-style checks).

### 7) Integration test matrix (config-driven)
- **Scope:** Add config-driven integration tests for filesystem, S3, PostgreSQL, and RocksDB paths via TestContainers.
- **Key changes:** Introduce config fixtures; spin up containers per backend; run shared ingest/retrieve scenarios through protocol surfaces.
- **Acceptance:** New sbt integration-test project/module green locally; gating CI job added or documented with runtime guard.

### 8) Release automation and metadata
- **Scope:** Prepare CI + build metadata for first publish.
- **Key changes:** Verify `version.sbt` coordinates; add CI workflow to run validation, build docs, and publish to Maven Central (or dry run); ensure doc nav checks run.
- **Acceptance:** CI pipeline defined; dry-run publish succeeds; release notes draft links docs and enumerates supported backends/APIs/CLI.

### 9) Replication/manifest docs
- **Scope:** Document replication model (stores/sectors/replicas) and manifest format with forward-compat guarantees.
- **Key changes:** Add docs with diagrams and examples; ensure terms match current code; link from module pages.
- **Acceptance:** `mdoc` passes; docs consistently use Graviton terminology; forward-compat story is explicit.

### 10) Apache Tika module page
- **Scope:** Add module page for Tika integration under `docs/modules/`.
- **Key changes:** Explain configuration, supported formats, and sample usage; include metrics/logging hooks if applicable.
- **Acceptance:** Page linked from Modules index; any code snippets compile or are guarded for `mdoc`.
