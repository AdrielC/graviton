# Contributing to Graviton

Graviton is a Scala 3 and ZIO content-addressed byte-storage runtime. Keep changes inside its [product boundary](docs/scope.md), pair behavior with executable proof, and describe only what the code actually does.

## Prerequisites

- JDK 21 or newer
- Node.js 20 or newer for documentation and Scala.js work
- Docker only for container-backed PostgreSQL, MinIO, Redis, or deployment tests

The repository includes `./sbt`; a global sbt installation and Git submodules are not required.

```bash
git clone https://github.com/AdrielC/graviton.git
cd graviton
TESTCONTAINERS=0 ./sbt compile test
```

## Module boundaries

- `graviton-core`: pure identity, refined types, codecs, range, Scan, and Transducer libraries
- `graviton-streams`: bounded ZIO Stream chunking and hashing utilities
- `graviton-runtime`: storage ports, ingest, transfer admission, maintenance, and tenant services
- `graviton-backend-laws`: reusable ZIO Test contracts
- `graviton-pdf`: bounded PDF-aware chunk selection through zio-pdf
- `modules/protocol`: shared models, HTTP, gRPC, and protobuf
- `modules/backend`: PostgreSQL, S3-compatible, and RocksDB adapters
- `modules/integration`: optional Shardcake and Redis or Valkey integrations
- `graviton-security`: authentication, authorization, rate policy, and audit
- `graviton-server`: executable wiring and the local server-rendered console
- `modules/frontend`: documentation-site Scala.js applications, not the server data plane

`modules/core`, `modules/db`, and `modules/pg` are unbuilt source trees. Do not import them from shipped code or document them as released modules.

## Engineering invariants

- Keep arbitrary payloads as `ZStream`; never collect a whole upload or download.
- Materialize bytes only behind a named compile-time refinement and a runtime `max + 1` overflow check.
- Keep file and protocol lengths as `Long`.
- Use `StoreError` or another domain algebra for expected failures. Preserve diagnostic causes without making callers inspect arbitrary exceptions.
- Keep resource acquisition and finalization in `Scope`.
- Make tenant, session, and caller context regional and fail closed at boundaries.
- Use bounded metric labels. Never add tenant, principal, session, object, digest, filename, or payload identity as a label.
- Keep document identity, search, extraction, embeddings, permissions, and workflow out of Graviton's byte substrate.

Run the policy checks directly:

```bash
./scripts/check-byte-streaming-hygiene.sh
./scripts/check-product-boundary.sh
python3 scripts/check-doc-truth.py
```

## Validation

For an ordinary code change:

```bash
TESTCONTAINERS=0 ./sbt scalafmtAll test
./scripts/verify-version-compatibility.sh
```

For public artifacts or server wiring:

```bash
./scripts/verify-external-consumer.sh
./scripts/audit-published-artifacts.sh
./sbt server/assembly
./scripts/smoke-packaged-server.sh
./scripts/verify-backup-restore.sh
```

Container-backed suites use the environment and pinned services in `.github/workflows/ci.yml`. Run only the focused integration you changed, or reproduce that CI job exactly.

## Documentation

Code examples that form part of the executable guide live under `docs/snippets`. After changing one:

```bash
./sbt syncDocSnippets
./sbt docs/mdoc checkDocSnippets
```

Build the complete site with the locked dependency graph:

```bash
npm ci --prefix docs
./sbt contentLab/test pdfContentLab/test buildDocsAssets
npm run docs:build --prefix docs
```

Every capability claim must identify whether it is released, implemented only on `main`, optional, target-qualified, or not shipped. Add or update evidence in `docs/status/implementation-evidence.json` when a major public claim changes.

## PostgreSQL changes

Update `modules/backend/graviton-pg/src/main/resources/ddl.sql`, exercise it against an empty PostgreSQL database, regenerate bindings with `./sbt "dbcodegen/run"`, and commit the DDL and generated sources together. The CI workflow is the canonical least-privilege and RLS setup.

## Pull requests

- Keep a PR focused and preserve compatibility unless the change explicitly declares a pre-1.0 boundary.
- Add success, failure, interruption, cleanup, and concurrency proof where applicable.
- State what was run end to end, what was verified structurally, and what remains target-specific.
- Run `git diff --check` and inspect tracked, modified, and untracked files before pushing.

## Releases

Versions come from annotated `vX.Y.Z` tags through sbt-dynver. Do not edit a version constant. A tag triggers the fail-closed release workflow, which validates the build, signs and publishes Maven modules, builds and signs the container, and creates the GitHub release only when all required jobs succeed.

By contributing, you agree that your contribution is licensed under Apache-2.0.
