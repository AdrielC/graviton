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

### Blob upload (single stream)

```http
POST /api/blobs
Content-Type: application/octet-stream
```

Response body: JSON `BlobId` string in the format:

- `<algo>:<digestHex>:<byteLength>`

### Blob download

```http
GET /api/blobs/:id
Accept: application/octet-stream
```

### Dashboard snapshot + event stream

```http
GET /api/datalake/dashboard
GET /api/datalake/dashboard/stream
```

### Convenience endpoints

```http
GET /api/stats
GET /api/schema
```

## See also

- **[Protocol stack](../modules/protocol.md)** — modules and wiring points
- **[Runtime ports](../runtime/ports.md)** — stable interfaces used by the protocol layers

