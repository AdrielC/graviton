# Configuration Reference (Current Server)

This page documents the **current, runnable** configuration surface for the `graviton-server` app (`./sbt "server/run"`). Today the server is configured via **environment variables** (see `graviton.server.Main`), not HOCON.

:::: warning Scope
This is the **current** server configuration contract. Other modules may expose additional configuration options that are not wired into the server yet.
::::

## TL;DR: pick a backend and set the env vars

### Option A: filesystem CAS (default, no external services)

```bash
./sbt "server/run"
```

The defaults persist blocks and framed manifests below `.graviton/`. Set `GRAVITON_FS_ROOT` or `GRAVITON_FS_BLOCK_PREFIX` only when you need a different layout.

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
| `POST /api/blobs` | upload | Uses the selected storage composition |
| `GET /api/blobs/:id` | download | Uses the selected storage composition |
| `HEAD /api/blobs/:id` | metadata headers | Checks existence without a response body |
| `DELETE /api/blobs/:id` | logical delete | Removes the manifest and retains shared blocks |

## Environment variables

### Server

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `GRAVITON_HTTP_PORT` | `8081` | no | Port for the HTTP server. |

### PostgreSQL (required for S3/MinIO or JDBC audit mode)

S3/MinIO mode uses PostgreSQL for manifest metadata via `PgDataSource.layerFromEnv`. Filesystem mode does not construct a data source. A JDBC audit sink also requires these variables.

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
| `GRAVITON_BLOB_BACKEND` | `fs` | no | Which storage composition to use: `fs`, `minio`, or `s3`. |

Notes:

- `minio` and `s3` currently share the **same env contract** (endpoint + access keys), and are best understood as “S3-compatible via MinIO-style credentials”.
- Filesystem mode stores blocks and manifests locally and is the zero-service default.

### Filesystem blocks and manifests (`GRAVITON_BLOB_BACKEND=fs`)

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `GRAVITON_FS_ROOT` | `./.graviton` | no | Root directory for all block data. |
| `GRAVITON_FS_BLOCK_PREFIX` | `cas/blocks` | no | Subdirectory prefix under `GRAVITON_FS_ROOT` used for block objects. |

#### Filesystem layout (exact)

Block files are stored under:

- `<GRAVITON_FS_ROOT>/<GRAVITON_FS_BLOCK_PREFIX>/<algo>/<hex>-<size>`

Example:

- `./.graviton/cas/blocks/blake3/0123abcd...-1048576`

`FsBlobManifestRepo` stores versioned manifest files under:

- `<GRAVITON_FS_ROOT>/cas/manifests/<algo>/<hex>-<size>.manifest`

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

- `algo`: `result.key.bits.algo.primaryName` (for example, `blake3` or `sha-256`)
- `digestHex`: `result.key.bits.digest.hex.value`
- `byteLength`: `result.key.bits.size`

### Validation behavior

- `GET /api/blobs/:id` validates the id and returns **400** if it cannot be parsed.
- Invalid ingest input returns **400** with a JSON error envelope; unexpected storage failures return a generic **500** without exposing arbitrary exception messages.

## How configuration is read (source pointers)

- **Server port / backend selection**: `modules/server/graviton-server/src/main/scala/graviton/server/Main.scala`
- **PostgreSQL env vars for S3/MinIO**: `modules/backend/graviton-pg/src/main/scala/graviton/backend/pg/PgDataSource.scala`
- **Filesystem manifest layout**: `modules/graviton-runtime/src/main/scala/graviton/runtime/stores/FsBlobManifestRepo.scala`
- **Filesystem block layout**: `modules/graviton-runtime/src/main/scala/graviton/runtime/stores/FsBlockStore.scala`
- **S3 block layout**: `modules/backend/graviton-s3/src/main/scala/graviton/backend/s3/S3BlockStore.scala`
- **Metrics endpoint**: `modules/protocol/graviton-http/src/main/scala/graviton/protocol/http/MetricsHttpApi.scala`

## Common misconfigurations (symptoms → fix)

### Missing PostgreSQL schema in S3/MinIO mode

Symptoms: S3/MinIO server startup or uploads fail, or PostgreSQL reports missing relations.

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
