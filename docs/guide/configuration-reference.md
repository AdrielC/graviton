# Configuration Reference (Current Server)

This page documents the **current, runnable** configuration surface for the `graviton-server` app (`./sbt "server/run"`). Today the server is configured via **environment variables** (see `graviton.server.Main`), not HOCON.

:::: warning Scope
This is the **current** server configuration contract. Other modules may expose additional configuration options that are not wired into the server yet.
::::

## Quick start (minimal env)

The server always needs PostgreSQL credentials (manifest metadata) and a block backend:

```bash
export PG_JDBC_URL="jdbc:postgresql://localhost:5432/graviton"
export PG_USERNAME="postgres"
export PG_PASSWORD="postgres"

export GRAVITON_BLOB_BACKEND="fs" # or "minio" / "s3"
export GRAVITON_FS_ROOT="./.graviton"
export GRAVITON_FS_BLOCK_PREFIX="cas/blocks"

./sbt "server/run"
```

## Environment variables

### Server

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `GRAVITON_HTTP_PORT` | `8081` | no | Port for the HTTP server. |

### Postgres (required)

The current server uses Postgres for manifests / metadata.

| Name | Default | Required | Meaning |
| --- | --- | --- | --- |
| `PG_JDBC_URL` | (none) | yes | JDBC URL for Postgres. |
| `PG_USERNAME` | (none) | yes | Postgres username. |
| `PG_PASSWORD` | (none) | yes | Postgres password. |

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

### S3/MinIO blocks (`GRAVITON_BLOB_BACKEND=s3|minio`)

The current server’s S3-compatible configuration is designed to work with MinIO (or other S3-compatible endpoints) using static access keys.

Required credentials / endpoint:

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
| `GRAVITON_S3_REGION` | `us-east-1` | no | Region passed to the AWS SDK client. For MinIO this is typically ignored, but must be syntactically valid. |

## How configuration is read (source pointers)

- **Server port / backend selection**: `modules/server/graviton-server/src/main/scala/graviton/server/Main.scala`
- **Postgres**: `modules/backend/graviton-pg/src/main/scala/graviton/backend/pg/PgDataSource.scala`
- **S3 block store env loader**: `modules/backend/graviton-s3/src/main/scala/graviton/backend/s3/S3BlockStore.scala`

## Common misconfigurations

### Missing Postgres schema

Symptoms: server starts, but uploads fail (500) or manifest operations error.

Fix:

```bash
psql -U postgres -d graviton -f modules/pg/ddl.sql
```

### S3/MinIO backend selected but MinIO env vars missing

Symptoms: server fails at startup with “Missing env var …”.

Fix: ensure `QUASAR_MINIO_URL`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` are set.

### Bucket does not exist

Symptoms: writes fail on first upload with S3 errors.

Fix: create the bucket before running uploads (MinIO example):

```bash
mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb local/graviton-blocks
```

