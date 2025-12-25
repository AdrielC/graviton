# CLI & Server Usage

Graviton does not ship a standalone CLI binary yet. Today, the easiest way to interact with a running node is via the demo HTTP endpoints (or by using the `BlobStore` / `BlockStore` APIs directly in Scala).

## Run the server

```bash
./sbt "server/run"
```

Defaults:

- HTTP port: `8081` (override with `GRAVITON_HTTP_PORT`)
- Health: `GET /api/health`
- Metrics: `GET /metrics`

## Configure backends (current server)

The server always expects Postgres credentials:

```bash
export PG_JDBC_URL="jdbc:postgresql://localhost:5432/graviton"
export PG_USERNAME="postgres"
export PG_PASSWORD="postgres"
```

Pick block storage:

### Filesystem blocks

```bash
export GRAVITON_BLOB_BACKEND="fs"
export GRAVITON_FS_ROOT="./.graviton"
export GRAVITON_FS_BLOCK_PREFIX="cas/blocks"
```

### MinIO/S3-compatible blocks

```bash
export GRAVITON_BLOB_BACKEND="s3"   # or "minio"
export QUASAR_MINIO_URL="http://localhost:9000"
export MINIO_ROOT_USER="minioadmin"
export MINIO_ROOT_PASSWORD="minioadmin"
export GRAVITON_S3_BLOCK_BUCKET="graviton-blocks"
export GRAVITON_S3_BLOCK_PREFIX="cas/blocks"
```

## Upload and download with curl

Upload a file:

```bash
curl -X POST --data-binary @/path/to/file "http://localhost:8081/api/blobs"
```

The response is a JSON string `BlobId` in the format `<algo>:<digestHex>:<byteLength>`.

Download it back:

```bash
curl -L "http://localhost:8081/api/blobs/<algo>:<digestHex>:<byteLength>" --output downloaded.bin
```

## See also

- [`api/http`](../api/http.md) for the full list of currently implemented endpoints.
