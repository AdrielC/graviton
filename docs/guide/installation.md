# Installation

Graviton is a pre-1.0 Scala 3 project. Source builds, tagged GitHub release artifacts, and signed modules under `io.github.adrielc` on Maven Central are supported distribution paths.

## Requirements

- JDK 21 or newer
- Git
- Node.js and npm only when building the VitePress site
- Docker only for the optional PostgreSQL and MinIO integration suites

PostgreSQL and MinIO are not required for the default server. It stores both blocks and manifests on the local filesystem.

## Build from source

```bash
git clone https://github.com/AdrielC/graviton.git
cd graviton

./sbt compile
TESTCONTAINERS=0 ./sbt test
```

Use the checked-in sbt launcher so the repository controls the sbt version.

## Prove the local CLI lifecycle

```bash
./scripts/verify-local-lifecycle.sh
```

The script creates an isolated temporary store, ingests a fixture, starts a fresh JVM for each later command, retrieves the bytes, verifies the digest, and compares the output with the original file.

## Run the default server

```bash
./sbt "server/run"
```

The default configuration uses:

- HTTP port `8081`
- gRPC port `9090`
- filesystem blocks below `.graviton/cas/blocks/`
- framed filesystem manifests below `.graviton/cas/manifests/`
- security disabled, with a startup warning

Verify it from another shell:

```bash
curl --fail --silent --show-error http://localhost:8081/api/health

blob_id="$(
  printf 'hello graviton\n' \
  | curl --fail --silent --show-error \
      --data-binary @- \
      http://localhost:8081/api/v1/blobs \
  | jq -r '.blob.id'
)"

curl --fail --silent --show-error \
  "http://localhost:8081/api/v1/blobs/$blob_id"
```

Stop and restart the server, then repeat the `GET`. The blob remains available because both blocks and manifests are durable.

See [Run Locally](./run-locally.md) for the complete POST, HEAD, GET, stats, restart, and delete walkthrough.

## Select a storage path

### Filesystem, the default

```bash
export GRAVITON_BLOB_BACKEND="fs"
export GRAVITON_FS_ROOT="./.graviton"
export GRAVITON_FS_BLOCK_PREFIX="cas/blocks"
```

No database or object store is constructed in this mode.

### S3 or MinIO blocks with PostgreSQL manifests

The shared-server path uses PostgreSQL for manifests when `GRAVITON_BLOB_BACKEND` is `s3` or `minio`. It also requires an existing S3-compatible bucket.

```bash
export GRAVITON_BLOB_BACKEND="minio"

export PG_JDBC_URL="jdbc:postgresql://localhost:5432/graviton"
export PG_USERNAME="postgres"
export PG_PASSWORD="postgres"

export QUASAR_MINIO_URL="http://localhost:9000"
export MINIO_ROOT_USER="minioadmin"
export MINIO_ROOT_PASSWORD="minioadmin"
export GRAVITON_S3_BLOCK_BUCKET="graviton-blocks"
export GRAVITON_S3_BLOCK_PREFIX="cas/blocks"
export GRAVITON_S3_REGION="us-east-1"
```

Apply the schema before startup:

```bash
psql -U postgres -d graviton -f modules/pg/ddl.sql
```

The exact container setup and bucket command are in [Run Locally](./run-locally.md).

### RocksDB

The RocksDB module contains a restart-safe key-value adapter with scoped native resources. It is not wired as a CAS block backend in the server yet.

## Build the documentation site

```bash
./sbt docs/mdoc checkDocSnippets buildDocsAssets
npm ci --prefix docs
npm run docs:build --prefix docs
```

`buildDocsAssets` generates Scaladoc and rebuilds the Scala.js assets used by the docs.

## Artifact availability

Published library coordinates use the `io.github.adrielc` organization. `scripts/verify-external-consumer.sh` publishes the current revision to an isolated local repository, resolves its generated POMs from an unrelated sbt build, runs a real CAS round trip, and fails on dependency metadata conflicts.

Every `v*` tag runs tests and packaged-server proof before creating the JAR, checksum, SPDX SBOM, provenance attestation, GHCR image, and GitHub release. Use only a version shown by the repository's Releases page. Maven Central coordinates become supported only after the release notes confirm a successful signed publication.

To run the same consumer proof locally:

```bash
./scripts/verify-external-consumer.sh
```

## Troubleshooting

### Unsupported backend

`GRAVITON_BLOB_BACKEND` must be `fs`, `s3`, or `minio`. Unset it to return to the filesystem default.

### Missing PostgreSQL relations

This applies only to S3/MinIO mode or a JDBC audit sink:

```bash
psql -U postgres -d graviton -f modules/pg/ddl.sql
```

### Missing bucket

Create `GRAVITON_S3_BLOCK_BUCKET` before the first upload in S3/MinIO mode. For local MinIO:

```bash
mc alias set local "$QUASAR_MINIO_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing local/"$GRAVITON_S3_BLOCK_BUCKET"
```

### Port conflict

Choose another listener port:

```bash
export GRAVITON_HTTP_PORT=18081
./sbt "server/run"
```

## Next steps

- [Getting Started](./getting-started.md)
- [Configuration Reference](./configuration-reference.md)
- [HTTP API](../api/http.md)
- [Storage Backends](./storage-backends.md)
