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
python3 scripts/check-doc-truth.py
./sbt docs/mdoc checkDocSnippets
```

The Python check validates the machine-readable implementation ledger, referenced source symbols, script paths, and selected stale-claim patterns. Mdoc and snippet checks compile the executable Scala examples.

If a checked snippet intentionally changes:

```bash
./sbt syncDocSnippets
./sbt docs/mdoc checkDocSnippets
```

## Documentation site

```bash
# Install the locked browser dependency graph first
npm ci --prefix docs

# Test and build the streamed analyzer, PDF editor, consoles, and Scaladoc
./sbt contentLab/test pdfContentLab/test buildDocsAssets

# Build VitePress
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
BLOB_ID="$(curl -fsS -X POST --data-binary @README.md http://localhost:8081/api/v1/blobs | jq -r '.blob.id')"
curl -fsS "http://localhost:8081/api/v1/blobs/$BLOB_ID/metadata" | jq .
curl -fsS -X POST "http://localhost:8081/api/v1/blobs/$BLOB_ID/verify" | jq .
curl -fsSI "http://localhost:8081/api/v1/blobs/$BLOB_ID"
curl -fsS "http://localhost:8081/api/v1/blobs/$BLOB_ID" --output /tmp/graviton-readme.md
cmp README.md /tmp/graviton-readme.md
```

The default path uses filesystem blocks and filesystem manifests below `.graviton/`; PostgreSQL and MinIO are not required.

Run `./scripts/verify-http-lifecycle.sh` against the running server to assert inventory, inspection, verification, retrieval, and deletion.

## Container-backed integration tests

The GitHub Actions workflow is the canonical setup. It starts PostgreSQL and MinIO, applies the versioned PostgreSQL migration set twice through `scripts/migrate-postgres.sh` to prove idempotency, creates the block bucket, and runs:

```bash
TESTCONTAINERS=1 \
GRAVITON_IT=1 \
GRAVITON_MINIO_IT=1 \
./sbt test
```

Required connection variables are documented in [docs/guide/configuration-reference.md](docs/guide/configuration-reference.md).

## Three-domain loss and repair qualification

```bash
./scripts/demo-three-domain.sh up
./scripts/qualify-three-domain.sh | jq .
```

This requires a responsive Docker engine. It uses the dedicated `graviton-three-domain` Compose project, three separate S3-compatible processes and volumes, PostgreSQL, Prometheus, and Grafana. The qualification deletes one of those dedicated object-store volumes, so do not reuse that Compose project name for data you intend to keep. GitHub Actions runs the same script and retains the commit-addressed proof, metrics, rules, dashboard, status, and logs for 90 days.

## Expected generated paths

| Artifact | Path |
| --- | --- |
| Streamed analyzer and bounded PDF editor inputs | `docs/.vitepress/generated/content-lab/`, `docs/.vitepress/generated/pdf-lab/` |
| Scala.js dashboard | `docs/public/js/` |
| Module Scaladoc | `docs/public/scaladoc/` |
| VitePress production site | `docs/.vitepress/dist/` |

Generated docs assets are build output. Review source changes separately from generated files before committing.
