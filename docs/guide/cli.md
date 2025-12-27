# CLI & Server Usage

Graviton does **not** ship a standalone CLI binary yet (there is no `cli` SBT project in this repo today). For now, “CLI usage” means:

- Use the **server** (`./sbt "server/run"`) and interact via **curl**
- Or call the **runtime APIs** (`BlobStore`, `BlockStore`) directly from Scala

## Run the server

```bash
./sbt "server/run"
```

Defaults:

- HTTP port: `8081` (override with `GRAVITON_HTTP_PORT`)
- Health: `GET /api/health`
- Metrics: `GET /metrics`

## Configure backends (current server)

The server expects Postgres credentials for manifest metadata:

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

### Health check

```bash
curl -fsS "http://localhost:8081/api/health" > /dev/null
```

### Upload a file

The upload endpoint accepts an octet stream and returns a JSON string `BlobId` in the format `<algo>:<digestHex>:<byteLength>`.

```bash
# Upload
BLOB_ID="$(
  curl -fsS \
    -H "Content-Type: application/octet-stream" \
    -X POST --data-binary @/path/to/file \
    "http://localhost:8081/api/blobs" \
  | jq -r .
)"

echo "Uploaded: $BLOB_ID"
```

If you don’t have `jq`, you can capture the raw JSON string and strip quotes manually:

```bash
BLOB_ID_RAW="$(curl -fsS -X POST --data-binary @/path/to/file "http://localhost:8081/api/blobs")"
BLOB_ID="${BLOB_ID_RAW%\"}"
BLOB_ID="${BLOB_ID#\"}"
echo "Uploaded: $BLOB_ID"
```

### Download it back

```bash
curl -fsS -L "http://localhost:8081/api/blobs/$BLOB_ID" --output downloaded.bin
```

Optional sanity check:

```bash
sha256sum /path/to/file downloaded.bin
```

### Convenience endpoints

```bash
curl -fsS "http://localhost:8081/api/stats"  | jq .
curl -fsS "http://localhost:8081/api/schema" | jq .
```

## See also

- [`api/http`](../api/http.md) for the full list of currently implemented endpoints.
