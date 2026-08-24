# HTTP Blob API

The current HTTP surface is runnable and contract-tested, but remains pre-1.0. Runtime APIs are still the compatibility anchor for embedded integrations.

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
BLOB_ID="$(
  curl -fsS \
    -H "Content-Type: application/octet-stream" \
    -X POST --data-binary @sample.bin \
    "http://localhost:8081/api/blobs" \
  | jq -r .
)"
```

Success returns `201 Created`, a JSON string containing the blob ID, plus `Location` and `ETag` headers. Empty bodies return `400 Bad Request`.

## Retrieve

```http
GET /api/blobs/:id
```

```bash
curl -fsS "http://localhost:8081/api/blobs/$BLOB_ID" --output retrieved.bin
```

Success returns `200 OK` with:

- `Content-Type: application/octet-stream`
- `Content-Length`
- `ETag`
- `Last-Modified`
- immutable `Cache-Control`

The service checks manifest existence before returning 200. A valid but unknown ID returns 404 instead of starting a body stream that fails later.

## Inspect without a body

```http
HEAD /api/blobs/:id
```

```bash
curl -fsSI "http://localhost:8081/api/blobs/$BLOB_ID"
```

HEAD returns the same status and metadata headers as GET with an empty body.

## Delete

```http
DELETE /api/blobs/:id
```

Success returns `204 No Content`. Deletion removes the logical manifest only. Shared content-addressed blocks are retained.

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
| 500 | `storage_failure` | Metadata lookup or deletion failed |
| 500 | `ingest_failed` | The backing store could not complete the write |

Unexpected server errors do not expose arbitrary exception messages.

## Health, metrics, and dashboard data

```http
GET /api/health
GET /metrics
GET /api/datalake/dashboard
GET /api/datalake/dashboard/stream
```

The dashboard endpoint begins with a source-backed reference snapshot. SSE updates are emitted only when application code publishes a real update; the service does not manufacture telemetry.

## Stability boundary

Authentication, route versioning, conditional requests, range reads, and idempotency keys remain release work. They are tracked in the repository `ROADMAP.md`.
