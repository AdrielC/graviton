# HTTP Blob API

The HTTP surface performs the real content-addressable storage lifecycle against the server's configured backends. It remains pre-1.0, so applications that need a stable compatibility boundary should embed the runtime API.

Default base URL: `http://localhost:8081`.

## Content IDs

Every blob ID is explicit and round-trippable:

```text
<algorithm>:<hex-digest>:<byte-length>
```

Example:

```text
sha-256:ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb:1
```

The digest length must match the selected algorithm and the byte length must be positive.

## Upload

```http
POST /api/blobs
Content-Type: application/octet-stream
```

```bash
UPLOAD_JSON="$(
  curl -fsS \
    -H 'Content-Type: application/octet-stream' \
    -X POST --data-binary @sample.bin \
    'http://localhost:8081/api/blobs'
)"
BLOB_ID="$(jq -r '.blob.id' <<<"$UPLOAD_JSON")"
```

Success returns `201 Created` after the manifest has been persisted. The JSON body reports durable blob metadata, real block counts from the write, and measured ingest duration:

```json
{
  "blob": {
    "id": "sha-256:...:42",
    "size": 42,
    "createdAt": 1787558400000,
    "digest": "...",
    "blockCount": 1
  },
  "freshBlocks": 1,
  "duplicateBlocks": 0,
  "durationSeconds": 0.012
}
```

The response also includes `Location` and `ETag` headers. Empty bodies return `400 Bad Request`.

## Durable inventory

```http
GET /api/blobs
```

The response is built from persisted manifests, not process counters. Restarting the filesystem server retains this inventory.

```bash
curl -fsS 'http://localhost:8081/api/blobs' | jq .
```

## Inspect a manifest

```http
GET /api/blobs/:id/metadata
```

This returns the blob summary and the exact persisted block references, including each block's content ID, byte offset, and size.

```bash
curl -fsS "http://localhost:8081/api/blobs/$BLOB_ID/metadata" | jq .
```

## Verify persisted bytes

```http
POST /api/blobs/:id/verify
```

The server streams the stored blob through the hash implementation and compares the digest and byte count with the requested content ID.

```bash
curl -fsS -X POST "http://localhost:8081/api/blobs/$BLOB_ID/verify" | jq .
```

## Retrieve

```http
GET /api/blobs/:id
```

```bash
curl -fsS "http://localhost:8081/api/blobs/$BLOB_ID" --output retrieved.bin
```

Success returns `200 OK` with `Content-Type`, `Content-Length`, `ETag`, `Last-Modified`, and immutable `Cache-Control` headers. A valid but unknown ID returns `404` before a body stream begins.

## Inspect without a body

```http
HEAD /api/blobs/:id
```

HEAD returns the same status and metadata headers as GET with an empty body.

## Delete

```http
DELETE /api/blobs/:id
```

Success returns `204 No Content`. Deletion removes the logical manifest. Shared content-addressed blocks are retained until a garbage collector is implemented.

## Health, counters, and metrics

```http
GET /api/health
GET /api/stats
GET /metrics
```

Health reports the running build version and process uptime. Stats and Prometheus metrics are process-lifetime observations. Use `GET /api/blobs` for durable inventory.

## Error envelope

Errors use JSON:

```json
{
  "error": "blob_not_found",
  "message": "Blob not found: sha-256:...:42"
}
```

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | `invalid_blob_id` | The path ID is not a valid content key |
| 400 | `invalid_blob` | The upload violates an ingest constraint |
| 404 | `blob_not_found` | The ID is valid but no manifest exists |
| 500 | `inventory_failure` | Durable inventory could not be read |
| 500 | `storage_failure` | Metadata lookup, retrieval, or deletion failed |
| 500 | `verification_failure` | Persisted bytes could not be read and hashed |
| 500 | `ingest_failed` | The backing store could not complete the write |

Unexpected server errors do not expose arbitrary exception messages.

## Executable proof

With the server running, execute the entire API lifecycle:

```bash
./scripts/verify-http-lifecycle.sh
```

Authentication, route versioning, conditional requests, range reads, and idempotency keys remain release work. They are tracked in `ROADMAP.md`.
