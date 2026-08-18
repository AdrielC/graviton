# Run Locally (Full Stack)

This guide gives you a **copy/paste** path to a working local Graviton setup, including Postgres, optional MinIO, the server, and a curl round-trip.

:::: warning What this guide targets
This is for the **current** `graviton-server` (`./sbt "server/run"`), which is configured by environment variables and exposes a demo-grade HTTP API under `/api/...`.
::::

## 1) Start Postgres (Docker)

```bash
docker rm -f graviton-postgres 2>/dev/null || true
docker run -d \
  --name graviton-postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=graviton \
  -p 5432:5432 \
  postgres:18
```

Wait for readiness:

```bash
until psql -h localhost -U postgres -d graviton -c "select 1" >/dev/null 2>&1; do
  sleep 1
done
```

Apply schema:

```bash
psql -h localhost -U postgres -d graviton -f modules/pg/ddl.sql
```

Export env vars for the server:

```bash
export PG_JDBC_URL="jdbc:postgresql://localhost:5432/graviton"
export PG_USERNAME="postgres"
export PG_PASSWORD="postgres"
```

## 2) Choose your block backend

### Option A (recommended first): filesystem blocks

```bash
export GRAVITON_BLOB_BACKEND="fs"
export GRAVITON_FS_ROOT="./.graviton"
export GRAVITON_FS_BLOCK_PREFIX="cas/blocks"
```

### Option B: MinIO blocks (S3-compatible)

Start MinIO:

```bash
docker rm -f graviton-minio 2>/dev/null || true
docker run -d \
  --name graviton-minio \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  -p 9000:9000 \
  -p 9001:9001 \
  minio/minio server /data --console-address ":9001"
```

Export env vars:

```bash
export GRAVITON_BLOB_BACKEND="minio"  # or "s3"
export QUASAR_MINIO_URL="http://localhost:9000"
export MINIO_ROOT_USER="minioadmin"
export MINIO_ROOT_PASSWORD="minioadmin"

export GRAVITON_S3_BLOCK_BUCKET="graviton-blocks"
export GRAVITON_S3_BLOCK_PREFIX="cas/blocks"
export GRAVITON_S3_REGION="us-east-1"
```

Create the bucket (using `mc` via Docker so you don’t need to install anything):

```bash
docker run --rm --network host minio/mc \
  alias set local "$QUASAR_MINIO_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

docker run --rm --network host minio/mc \
  mb local/"$GRAVITON_S3_BLOCK_BUCKET"
```

## 3) Start the server

```bash
export GRAVITON_HTTP_PORT=8081
./sbt "server/run"
```

In another terminal, verify it’s alive:

```bash
curl -fsS "http://localhost:8081/api/health" | jq .
curl -fsS "http://localhost:8081/metrics" | head -50
```

## 4) Upload + download round-trip (curl)

Create a test file:

```bash
printf "hello graviton\n" > sample.txt
```

Upload:

```bash
BLOB_ID="$(
  curl -fsS \
    -H "Content-Type: application/octet-stream" \
    -X POST --data-binary @sample.txt \
    "http://localhost:8081/api/blobs" \
  | jq -r .
)"

echo "$BLOB_ID"
```

Download:

```bash
curl -fsS -L "http://localhost:8081/api/blobs/$BLOB_ID" --output downloaded.txt
```

Verify:

```bash
sha256sum sample.txt downloaded.txt
diff -u sample.txt downloaded.txt
```

## 5) Dashboard snapshot + stream (optional)

Snapshot:

```bash
curl -fsS "http://localhost:8081/api/datalake/dashboard" | jq .
```

SSE stream:

```bash
curl -N "http://localhost:8081/api/datalake/dashboard/stream"
```

## Next steps

- **Configuration details**: see [Configuration Reference](./configuration-reference.md)
- **HTTP surface**: see [HTTP API](../api/http.md)
- **Binary model**: see [Binary Streaming Guide](./binary-streaming.md)

