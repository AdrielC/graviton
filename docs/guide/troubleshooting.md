# Troubleshooting

This page is a symptom-driven checklist for the current server (`./sbt "server/run"`).

## Server won’t start

### “Missing env 'PG_JDBC_URL' / 'PG_USERNAME' / 'PG_PASSWORD'”

Cause: the server always requires Postgres credentials.

Fix:

```bash
export PG_JDBC_URL="jdbc:postgresql://localhost:5432/graviton"
export PG_USERNAME="postgres"
export PG_PASSWORD="postgres"
```

### “Unsupported GRAVITON_BLOB_BACKEND='…'”

Cause: `GRAVITON_BLOB_BACKEND` must be one of `fs`, `s3`, or `minio`.

Fix:

```bash
export GRAVITON_BLOB_BACKEND="fs"
```

### “Missing env var 'QUASAR_MINIO_URL' / 'MINIO_ROOT_USER' / 'MINIO_ROOT_PASSWORD'”

Cause: you selected `GRAVITON_BLOB_BACKEND=s3|minio` but didn’t provide S3 endpoint credentials.

Fix: either set the MinIO env vars, or switch to filesystem blocks.

```bash
export GRAVITON_BLOB_BACKEND="fs"
```

## `/api/health` works, but uploads fail

### Upload returns 500 and mentions Postgres tables / relations

Cause: Postgres is reachable, but the schema wasn’t applied.

Fix:

```bash
psql -U postgres -d graviton -f modules/pg/ddl.sql
```

### Upload returns 500 on MinIO/S3 mode

Common causes:

- The bucket `GRAVITON_S3_BLOCK_BUCKET` does not exist.
- Your MinIO credentials are wrong.

Fix (bucket creation with Docker `mc`):

```bash
docker run --rm --network host minio/mc \
  alias set local "$QUASAR_MINIO_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

docker run --rm --network host minio/mc \
  mb local/"$GRAVITON_S3_BLOCK_BUCKET"
```

### Upload returns 500 “Empty blobs are not supported…”

Cause: the ingest pipeline rejects empty bodies (size must be > 0).

Fix: ensure your client is actually sending bytes (for curl, use `--data-binary @file` and confirm the file is non-empty).

## Downloads fail

### `GET /api/blobs/:id` returns 400

Cause: the blob id could not be parsed.

Blob IDs must be:

- `<algo>:<digestHex>:<byteLength>`

Example:

- `blake3:7b1d...:12`

### Download returns 500 for “not found”

Current behavior is not yet a stable contract: missing blobs may surface as 5xx depending on backend and where the error occurs in streaming.

If you suspect a config issue, validate:

- Postgres is reachable and has the schema applied
- Your selected block backend is pointing at the correct data (filesystem root/prefix or MinIO bucket/prefix)

See **[Configuration Reference](./configuration-reference.md)**.

## Metrics endpoint issues

### `/metrics` returns 404

Cause: you’re not hitting the current server (or something is proxying away that route).

The current server mounts metrics at:

- `GET /metrics`

## Demo UI can’t reach your server

The docs demo defaults to `http://localhost:8081` via:

```html
<meta name="graviton-api-url" content="http://localhost:8081" />
```

Fix: update that meta tag in `docs/demo.md` if you changed ports, or run the server on 8081.

## Still stuck?

- Re-run the complete local recipe in **[Run Locally (Full Stack)](./run-locally.md)**.
- Check the exact env vars the server sees: `env | sort | grep -E '^(PG_|GRAVITON_|MINIO_|QUASAR_)'`.
