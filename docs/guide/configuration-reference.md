# Configuration Reference (Current Server)

This page documents the **current, runnable** configuration surface for the `graviton-server` app (`./sbt "server/run"`). Today the server is configured via **environment variables** (see `graviton.server.Main`), not HOCON.

:::: warning Scope
This is the **current** server configuration contract. Other modules may expose additional configuration options that are not wired into the server yet.
::::

## TL;DR: pick a backend and set the env vars

### Option A: filesystem blocks (simplest local dev)

```bash
export PG_JDBC_URL="jdbc:postgresql://localhost:5432/graviton"
export PG_USERNAME="postgres"
export PG_PASSWORD="postgres"

export GRAVITON_BLOB_BACKEND="fs"
export GRAVITON_FS_ROOT="./.graviton"
export GRAVITON_FS_BLOCK_PREFIX="cas/blocks"

./sbt "server/run"
```

### Option B: MinIO / S3-compatible blocks

```bash
export PG_JDBC_URL="jdbc:postgresql://localhost:5432/graviton"
export PG_USERNAME="postgres"
export PG_PASSWORD="postgres"

export GRAVITON_BLOB_BACKEND="minio" # or "s3"
export QUASAR_MINIO_URL="http://localhost:9000"
export MINIO_ROOT_USER="minioadmin"
export MINIO_ROOT_PASSWORD="minioadmin"

# Optional (defaults shown)
export GRAVITON_S3_BLOCK_BUCKET="graviton-blocks"
export GRAVITON_S3_BLOCK_PREFIX="cas/blocks"
export GRAVITON_S3_REGION="us-east-1"

./sbt "server/run"
```

## HTTP endpoints affected by configuration

| Path | Meaning | Notes |
| --- | --- | --- |
| `GET /api/health` | health check | Always available when server is up |
| `GET /metrics` | Prometheus scrape | Exposes `text/plain; version=0.0.4` (metric names are evolving) |
| `POST /api/blobs` | upload | Requires Postgres + chosen block backend to be correctly configured |
| `GET /api/blobs/:id` | download | Requires Postgres + chosen block backend to be correctly configured |

## Environment variables

### Server

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `GRAVITON_HTTP_PORT` | `8081` | no | Port for the HTTP server. |

### Postgres (required)

The current server uses Postgres for manifest metadata via `PgDataSource.layerFromEnv`.

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `PG_JDBC_URL` | (none) | yes | JDBC URL for Postgres. |
| `PG_USERNAME` | (none) | yes | Postgres username. |
| `PG_PASSWORD` | (none) | yes | Postgres password. |

**You must also apply the schema**:

```bash
psql -U postgres -d graviton -f modules/pg/ddl.sql
```

### Block backend selection

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `GRAVITON_BLOB_BACKEND` | `s3` | no | Which block store implementation to use: `fs`, `minio`, or `s3`. |

Notes:

- `minio` and `s3` currently share the **same env contract** (endpoint + access keys), and are best understood as “S3-compatible via MinIO-style credentials”.
- Filesystem mode is the simplest local dev setup.

### Filesystem blocks (`GRAVITON_BLOB_BACKEND=fs`)

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `GRAVITON_FS_ROOT` | `./.graviton` | no | Root directory for all block data. |
| `GRAVITON_FS_BLOCK_PREFIX` | `cas/blocks` | no | Subdirectory prefix under `GRAVITON_FS_ROOT` used for block objects. |

#### Filesystem layout (exact)

From `FsBlockStore`, block files are stored under:

- `<GRAVITON_FS_ROOT>/<GRAVITON_FS_BLOCK_PREFIX>/<algo>/<hex>-<size>`

Example:

- `./.graviton/cas/blocks/blake3/0123abcd...-1048576`

### S3/MinIO blocks (`GRAVITON_BLOB_BACKEND=s3|minio`)

Required endpoint + credentials (used by `S3Config.fromEndpointEnv`):

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `QUASAR_MINIO_URL` | (none) | yes | S3 endpoint URL (for MinIO: `http://localhost:9000`). |
| `MINIO_ROOT_USER` | (none) | yes | Access key id. |
| `MINIO_ROOT_PASSWORD` | (none) | yes | Secret access key. |

Block object layout:

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `GRAVITON_S3_BLOCK_BUCKET` | `graviton-blocks` | no | Bucket used for block objects. |
| `GRAVITON_S3_BLOCK_PREFIX` | `cas/blocks` | no | Key prefix for block objects inside the bucket. |
| `GRAVITON_S3_REGION` | `us-east-1` | no | Region passed to the AWS SDK client. |

#### S3 object key layout (exact)

From `S3BlockStore`, block objects are written under:

- `<GRAVITON_S3_BLOCK_PREFIX>/<algo>/<hex>-<size>`

Example:

- `cas/blocks/blake3/0123abcd...-1048576`

#### Bucket creation (MinIO)

You must ensure `GRAVITON_S3_BLOCK_BUCKET` exists before your first upload.

If you have `mc` installed:

```bash
mc alias set local "$QUASAR_MINIO_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb local/"$GRAVITON_S3_BLOCK_BUCKET"
```

If you don’t have `mc`, you can run it via Docker:

```bash
docker run --rm --network host minio/mc \
  alias set local "$QUASAR_MINIO_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

docker run --rm --network host minio/mc \
  mb local/"$GRAVITON_S3_BLOCK_BUCKET"
```

## Blob IDs (HTTP)

The HTTP API uses a string `BlobId` rendered as:

- `<algo>:<digestHex>:<byteLength>`

This is produced on upload by `HttpApi` from the `BinaryKey.Blob`:

- `algo`: `result.key.bits.algo.primaryName` (e.g. `blake3`, `sha256`)
- `digestHex`: `result.key.bits.digest.hex.value`
- `byteLength`: `result.key.bits.size`

### Validation behavior

- `GET /api/blobs/:id` validates the id and returns **400** if it cannot be parsed.
- Upload failures currently return **500** with a plain-text message (this is not yet a stable error model).

## How configuration is read (source pointers)

- **Server port / backend selection**: `modules/server/graviton-server/src/main/scala/graviton/server/Main.scala`
- **Postgres env vars**: `modules/backend/graviton-pg/src/main/scala/graviton/backend/pg/PgDataSource.scala`
- **Filesystem block layout**: `modules/graviton-runtime/src/main/scala/graviton/runtime/stores/FsBlockStore.scala`
- **S3 block layout**: `modules/backend/graviton-s3/src/main/scala/graviton/backend/s3/S3BlockStore.scala`
- **Metrics endpoint**: `modules/protocol/graviton-http/src/main/scala/graviton/protocol/http/MetricsHttpApi.scala`

## Common misconfigurations (symptoms → fix)

### Missing Postgres schema

Symptoms: server starts, but uploads fail (500), or Postgres errors about missing relations.

Fix:

```bash
psql -U postgres -d graviton -f modules/pg/ddl.sql
```

### S3/MinIO backend selected but MinIO env vars missing

Symptoms: server fails at startup with “Missing env var …”.

Fix: set `QUASAR_MINIO_URL`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, or switch to filesystem blocks.

### Bucket does not exist

Symptoms: first upload fails with S3 errors.

Fix: create the bucket (see “Bucket creation (MinIO)” above).

