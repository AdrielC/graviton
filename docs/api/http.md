# HTTP API (Status + Intended Shape)

Graviton’s HTTP layer is implemented in `modules/protocol/graviton-http`.

## Current status

- The server-side `HttpApi` is currently a **stub** (it responds with a simple `"ok"` text response while routing/decoding is being built out).
- Client-side helpers (for uploads, etc.) exist, but the public “stable” REST surface is still evolving.

If you are integrating today, prefer the runtime APIs (`BlobStore`, `BlockStore`) or gRPC where available.

## Intended API shape (subject to change)

This section captures the **intended** direction for the REST surface so we can keep documentation and client work aligned. Treat these as a draft until `HttpApi` is fully wired.

### Blob upload (single stream)

```http
POST /api/v1/blobs
Content-Type: application/octet-stream
```

### Blob download

```http
GET /api/v1/blobs/:key
```

### Range reads

```http
GET /api/v1/blobs/:key
Range: bytes=0-1023
```

### Health + metrics

```http
GET /health
GET /metrics
```

## See also

- **[Protocol stack](../modules/protocol.md)** — module overview and current TODOs
- **[Runtime ports](../runtime/ports.md)** — stable interfaces used by protocol layers

