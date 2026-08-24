# Build and Test

## Prerequisites

- JDK 21 or newer
- Node.js 20 or newer for the documentation site
- Docker only for PostgreSQL and MinIO integration tests

The repository includes its sbt launcher. A separate global sbt installation is not required.

## Fast feedback

```bash
# Compile everything
./sbt compile

# Focused modules
TESTCONTAINERS=0 ./sbt 'core/test' 'streams/test' 'runtime/test'

# Check formatting without rewriting files
./sbt scalafmtCheckAll
```

## Required JVM validation

```bash
TESTCONTAINERS=0 ./sbt scalafmtAll test
```

`TESTCONTAINERS=0` runs the deterministic local suite without requiring PostgreSQL, MinIO, or Docker. Integration suites are gated by `GRAVITON_IT=1` and `GRAVITON_MINIO_IT=1` in CI.

## Executable documentation

```bash
./sbt docs/mdoc checkDocSnippets
```

If a checked snippet intentionally changes:

```bash
./sbt syncDocSnippets
./sbt docs/mdoc checkDocSnippets
```

## Documentation site

```bash
# Build Scala.js consoles and generated Scaladoc
./sbt buildDocsAssets

# Install the locked dependency graph and build VitePress
npm ci --prefix docs
npm run docs:build --prefix docs

# Preview the production output
npm run docs:preview --prefix docs
```

The preview is served under the configured VitePress base path. GitHub Pages derives that path from `GITHUB_REPOSITORY`; local builds default to `/`.

For a faster docs-only edit that does not touch Scala.js or Scaladoc:

```bash
npm ci --prefix docs
npm run docs:dev --prefix docs
```

## One-command CAS smoke test

```bash
./scripts/verify-local-lifecycle.sh
```

This check uses separate CLI runs to prove that filesystem manifests survive process restarts. It prints the temporary directory instead of deleting it so the resulting layout remains inspectable.

## Self-contained HTTP server

```bash
./sbt "server/run"

# In another terminal
BLOB_ID="$(curl -fsS -X POST --data-binary @README.md http://localhost:8081/api/blobs | jq -r '.blob.id')"
curl -fsS "http://localhost:8081/api/blobs/$BLOB_ID/metadata" | jq .
curl -fsS -X POST "http://localhost:8081/api/blobs/$BLOB_ID/verify" | jq .
curl -fsSI "http://localhost:8081/api/blobs/$BLOB_ID"
curl -fsS "http://localhost:8081/api/blobs/$BLOB_ID" --output /tmp/graviton-readme.md
cmp README.md /tmp/graviton-readme.md
```

The default path uses filesystem blocks and filesystem manifests below `.graviton/`; PostgreSQL and MinIO are not required.

Run `./scripts/verify-http-lifecycle.sh` against the running server to assert inventory, inspection, verification, retrieval, and deletion.

## Container-backed integration tests

The GitHub Actions workflow is the canonical setup. It starts PostgreSQL and MinIO, applies `modules/pg/ddl.sql`, creates the block bucket, and runs:

```bash
TESTCONTAINERS=1 \
GRAVITON_IT=1 \
GRAVITON_MINIO_IT=1 \
./sbt test
```

Required connection variables are documented in [docs/guide/configuration-reference.md](docs/guide/configuration-reference.md).

## Expected generated paths

| Artifact | Path |
| --- | --- |
| Scala.js dashboard | `docs/public/js/` |
| Quasar Scala.js demo | `docs/public/quasar/js/` |
| Module Scaladoc | `docs/public/scaladoc/` |
| VitePress production site | `docs/.vitepress/dist/` |

Generated docs assets are build output. Review source changes separately from generated files before committing.
