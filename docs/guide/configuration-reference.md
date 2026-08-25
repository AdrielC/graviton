# Configuration Reference

This page documents the current runnable configuration surface for `graviton-server`. The server reads environment variables through ZIO Config.

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
| `GET /api/health/live` | liveness | Always available when the process is up |
| `GET /api/health/ready` | backend readiness | Checks the configured block and manifest stores |
| `GET /metrics` | Prometheus scrape | Exposes `text/plain; version=0.0.4` (metric names are evolving) |
| `POST /api/v1/blobs` | upload | Uses the selected storage composition |
| `GET /api/v1/blobs/:id` | download | Supports ranges and conditional requests |
| `HEAD /api/v1/blobs/:id` | metadata headers | Checks existence without a response body |
| `DELETE /api/v1/blobs/:id` | logical delete | Removes the manifest and retains shared blocks |

The `/api/blobs` aliases remain available with `Deprecation: true` and a successor `Link` header.

## Environment variables

### Server

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `GRAVITON_HTTP_PORT` | `8081` | no | Port for the HTTP server. |
| `GRAVITON_CHUNK_SIZE` | `1048576` | no | Fixed ingest block size in bytes. |

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

- `minio` and `s3` select the same S3-compatible adapter.
- Set `QUASAR_MINIO_URL` for an explicit MinIO-style endpoint and credentials.
- Without an explicit endpoint, the AWS SDK default credential provider chain is used.
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

Endpoint and explicit credentials for MinIO:

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `QUASAR_MINIO_URL` | (none) | only for explicit endpoint | S3-compatible endpoint URL, such as `http://localhost:9000`. |
| `MINIO_ROOT_USER` | (none) | with endpoint | Access key id. |
| `MINIO_ROOT_PASSWORD` | (none) | with endpoint | Secret access key. |

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

Quarantined objects use the configured block prefix followed by `.graviton-quarantine/`. Applications should access them through `BlockMaintenance`, not by constructing object keys.

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

## Security

Security is disabled by default. When enabled, issuer and audience are required. Configure an HTTPS JWKS URI for production RS256 verification, or a development shared secret only for local proof.

| Name | Default | Meaning |
| --- | --- | --- |
| `GRAVITON_SECURITY_ENABLED` | `false` | Require bearer authentication and capability checks. |
| `GRAVITON_SECURITY_OIDC_ISSUER` | none | Exact expected `iss` claim. |
| `GRAVITON_SECURITY_OIDC_AUDIENCE` | none | Required token audience. |
| `GRAVITON_SECURITY_OIDC_JWKS_URI` | none | Absolute HTTPS JWKS URI for RS256 key lookup and rotation. |
| `GRAVITON_SECURITY_JWKS_CACHE_TTL` | `10m` | Remote key cache lifetime. |
| `GRAVITON_SECURITY_CLOCK_SKEW_SECONDS` | `30` | Allowed JWT clock skew. |
| `GRAVITON_SECURITY_REQUIRE_TLS` | `false` | Reject protected requests outside the configured HTTPS trust boundary. |
| `GRAVITON_SECURITY_TRUST_PROXY_HEADERS` | `false` | Trust `X-Forwarded-Proto`; enable only behind a sanitizing proxy. |
| `GRAVITON_SECURITY_CORS_ALLOWED_ORIGINS` | empty | Comma-separated exact browser origins. |
| `GRAVITON_SECURITY_RATE_LIMIT_PER_PRINCIPAL_PER_SEC` | `100` | Per-principal request budget. |
| `GRAVITON_SECURITY_RATE_LIMIT_UPLOAD_BYTES_PER_SEC` | `10485760` | Per-principal streamed upload-byte budget. |
| `GRAVITON_SECURITY_RATE_LIMIT_DOWNLOAD_BYTES_PER_SEC` | `52428800` | Per-principal streamed download-byte budget. |
| `GRAVITON_SECURITY_MAX_REQUEST_BYTES` | `5368709120` | Maximum upload size, enforced while streaming; valid range is 1 byte through 1 TiB. |
| `GRAVITON_SECURITY_AUDIT_BACKEND` | `memory` | `memory` or `jdbc`. |
| `GRAVITON_SECURITY_AUTHORIZATION_BACKEND` | `token` | JWT capability checks or `jdbc` ACL augmentation. |
| `GRAVITON_SECURITY_DEV_SHARED_SECRET` | none | Enables HS256 and `/dev/token`; never set in production. |

`/api/stats` and `/metrics` require `observability.read` when security is enabled. Blob endpoints require the corresponding `blob.read`, `blob.write`, or `blob.delete` capability.

## Blob IDs (HTTP)

The HTTP API uses a string `BlobId` rendered as:

- `<algo>:<digestHex>:<byteLength>`

This is produced on upload by `HttpApi` from the `BinaryKey.Blob`:

- `algo`: `result.key.bits.algo.primaryName` (for example, `blake3` or `sha-256`)
- `digestHex`: `result.key.bits.digest.hex.value`
- `byteLength`: `result.key.bits.size`

### Validation behavior

- `GET /api/v1/blobs/:id` validates the id and returns **400** if it cannot be parsed.
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

### MinIO endpoint selected but credentials missing

Symptoms: server fails at startup with “Missing env var …”.

Fix: set both `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD`, unset `QUASAR_MINIO_URL` to use the AWS default credential chain, or switch to filesystem blocks.

### Bucket does not exist

Symptoms: first upload fails with S3 errors.

Fix: create the bucket (see “Bucket creation (MinIO)” above).
