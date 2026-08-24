# Run Locally

The default Graviton server is a self-contained filesystem CAS. PostgreSQL and MinIO are optional deployment choices, not local-development prerequisites.

:::: warning Security boundary
Security is disabled by default and the server logs that posture at startup. Bind it only to a trusted development environment until production authentication and TLS termination are configured.
::::

## 1. Start the server

```bash
./sbt "server/run"
```

Blocks are written below `.graviton/cas/blocks/` and framed manifests below `.graviton/cas/manifests/`.

In another terminal:

```bash
curl -fsS "http://localhost:8081/api/health" | jq .
curl -fsS "http://localhost:8081/metrics" | head -50
```

## 2. Exercise the blob lifecycle

```bash
printf "hello graviton\n" > sample.txt

BLOB_ID="$(
  curl -fsS \
    -H "Content-Type: application/octet-stream" \
    -X POST --data-binary @sample.txt \
    "http://localhost:8081/api/blobs" \
  | jq -r .
)"

curl -fsSI "http://localhost:8081/api/blobs/$BLOB_ID"
curl -fsS "http://localhost:8081/api/blobs/$BLOB_ID" --output downloaded.txt
cmp sample.txt downloaded.txt
curl -fsS "http://localhost:8081/api/stats" | jq .
```

Stop and restart the server, then repeat the `GET`. Filesystem manifests make the object available to the fresh process. Process counters intentionally reset on restart.

## 3. Choose a different filesystem root

```bash
export GRAVITON_FS_ROOT="/tmp/graviton-data"
export GRAVITON_FS_BLOCK_PREFIX="cas/blocks"
export GRAVITON_HTTP_PORT=8081

./sbt "server/run"
```

## 4. Optional S3/MinIO plus PostgreSQL mode

The shared-server composition stores blocks in S3-compatible object storage and manifests in PostgreSQL.

Start PostgreSQL and apply the schema:

```bash
docker run -d \
  --name graviton-postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=graviton \
  -p 5432:5432 \
  postgres:16

until PGPASSWORD=postgres psql -h localhost -U postgres -d graviton -c "select 1" >/dev/null 2>&1; do
  sleep 1
done

PGPASSWORD=postgres psql \
  -h localhost -U postgres -d graviton \
  -f modules/pg/ddl.sql
```

Start MinIO:

```bash
docker run -d \
  --name graviton-minio \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  -p 9000:9000 \
  -p 9001:9001 \
  minio/minio server /data --console-address ":9001"
```

Create the bucket with an installed `mc` client, or use the equivalent MinIO console action:

```bash
mc alias set local http://localhost:9000 minioadmin minioadmin
mc mb --ignore-existing local/graviton-blocks
```

Configure and start Graviton:

```bash
export GRAVITON_BLOB_BACKEND="minio"
export PG_JDBC_URL="jdbc:postgresql://localhost:5432/graviton"
export PG_USERNAME="postgres"
export PG_PASSWORD="postgres"
export QUASAR_MINIO_URL="http://localhost:9000"
export MINIO_ROOT_USER="minioadmin"
export MINIO_ROOT_PASSWORD="minioadmin"
export GRAVITON_S3_BLOCK_BUCKET="graviton-blocks"

./sbt "server/run"
```

Run the same curl lifecycle from step 2. The CI workflow exercises this composition with PostgreSQL and MinIO services.

## Dashboard reference data

```bash
curl -fsS "http://localhost:8081/api/datalake/dashboard" | jq .
curl -N "http://localhost:8081/api/datalake/dashboard/stream"
```

The initial dashboard payload is a source-backed capability snapshot. The SSE route emits only updates explicitly published by application code.

## Next steps

- [Configuration Reference](./configuration-reference.md)
- [HTTP API](../api/http.md)
- [Storage Backends](./storage-backends.md)
- [Binary Streaming Guide](./binary-streaming.md)
