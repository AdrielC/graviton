# Troubleshooting

This page is a symptom-driven checklist for the current server (`./sbt "server/run"`).

## Server won’t start

### “Missing env 'PG_JDBC_URL' / 'PG_USERNAME' / 'PG_PASSWORD'”

Cause: `GRAVITON_BLOB_BACKEND` is set to `s3` or `minio`, which uses PostgreSQL manifests.

Fix: supply the PostgreSQL settings for that deployment, or set `GRAVITON_BLOB_BACKEND=fs` for the self-contained filesystem server.

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

### Download returns 404

The content ID is valid, but its manifest is not present in the configured repository. This is expected after `DELETE`, which removes the logical manifest while retaining shared blocks.

If you suspect a config issue, validate:

- Postgres is reachable and has the schema applied
- Your selected block backend is pointing at the correct data (filesystem root/prefix or MinIO bucket/prefix)

See **[Configuration Reference](./configuration-reference.md)**.

## Metrics endpoint issues

### `/metrics` returns 404

Cause: you’re not hitting the current server (or something is proxying away that route).

The current server mounts metrics at:

- `GET /metrics`

## Operations console can’t reach your server

The console defaults to `http://localhost:8081`. You can set the endpoint with the connection bar or an `api` query parameter:

```text
http://localhost:5173/demo?api=http://localhost:18081
```

If the API is running on another origin, use the default security-disabled local mode, which installs CORS headers. The console reports request failures directly and does not load substitute data.

## Still stuck?

- Re-run the complete local recipe in **[Run Locally (Full Stack)](./run-locally.md)**.
- Check the exact env vars the server sees: `env | sort | grep -E '^(PG_|GRAVITON_|MINIO_|QUASAR_)'`.
