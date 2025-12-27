# HTTP API (Current Status)

Graviton’s HTTP layer is implemented in `modules/protocol/graviton-http` and wired by the server in `modules/server/graviton-server`.

## Current status

- The HTTP surface is **usable for demos and local development**, but it is not yet a versioned/stable API.
- The server currently exposes **blob upload/download**, a **dashboard snapshot + SSE stream**, plus **health/stats/schema** helpers.

If you are integrating today, prefer the runtime APIs (`BlobStore`, `BlockStore`). The REST surface will likely evolve as auth, routing, and error models firm up.

## Endpoints (as implemented today)

All paths below are relative to the server base URL (default: `http://localhost:8081`).

### Health + metrics

```http
GET /api/health
GET /metrics
```

#### Example: health

```bash
curl -fsS "http://localhost:8081/api/health" | jq .
```

Example response:

```json
{"status":"ok","version":"dev","uptime":12345}
```

### Blob upload (single stream)

```http
POST /api/blobs
Content-Type: application/octet-stream
```

Response body: JSON `BlobId` string in the format:

- `<algo>:<digestHex>:<byteLength>`

#### Example: upload

```bash
BLOB_ID="$(
  curl -fsS \
    -H "Content-Type: application/octet-stream" \
    -X POST --data-binary @/path/to/file \
    "http://localhost:8081/api/blobs" \
  | jq -r .
)"

echo "$BLOB_ID"
```

### Blob download

```http
GET /api/blobs/:id
Accept: application/octet-stream
```

#### Example: download

```bash
curl -fsS -L "http://localhost:8081/api/blobs/$BLOB_ID" --output downloaded.bin
```

#### Expected error modes (current behavior)

Because this API is not yet stabilized, error bodies are not a firm contract. In general:

- **404**: unknown blob id / not found
- **500**: server misconfiguration (most often missing Postgres schema or S3/MinIO bucket)

### Dashboard snapshot + event stream

```http
GET /api/datalake/dashboard
GET /api/datalake/dashboard/stream
```

#### Example: snapshot

```bash
curl -fsS "http://localhost:8081/api/datalake/dashboard" | jq .
```

#### Example: stream (SSE)

```bash
curl -N "http://localhost:8081/api/datalake/dashboard/stream"
```

### Convenience endpoints

```http
GET /api/stats
GET /api/schema
```

#### Example: stats

```bash
curl -fsS "http://localhost:8081/api/stats" | jq .
```

#### Example: schema

```bash
curl -fsS "http://localhost:8081/api/schema" | jq .
```

## See also

- **[Protocol stack](../modules/protocol.md)** — modules and wiring points
- **[Runtime ports](../runtime/ports.md)** — stable interfaces used by the protocol layers

