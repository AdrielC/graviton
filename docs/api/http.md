# HTTP Blob API

The pre-1.0 HTTP surface is `/api/v1`. Graviton ships one blob contract and no compatibility aliases.

Default base URL: `http://localhost:8081`.

## Authentication

When security is enabled, send an OIDC bearer token:

```http
Authorization: Bearer <token>
```

Blob reads, writes, and deletes require `blob.read`, `blob.write`, and `blob.delete`. Stats and metrics require `observability.read`. Health endpoints remain public for orchestrator probes.

The development shared-secret mode exposes `POST /dev/token` for local testing. It must not be enabled in production.

### Browser origins

Security-enabled deployments answer unauthenticated `OPTIONS` preflights on the canonical blob routes so a browser can subsequently send `Authorization`. The preflight still fails closed unless the `Origin` exactly matches `GRAVITON_SECURITY_CORS_ALLOWED_ORIGINS`, the requested method exists on the route, and every requested header is in the API allow list. Actual requests still require a valid bearer token and capability.

## Content IDs

Every blob ID is explicit and round-trippable:

```text
<algorithm>:<hex-digest>:<byte-length>
```

Example:

```text
sha-256:ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb:1
```

## Upload

```http
POST /api/v1/blobs
Content-Type: application/octet-stream
```

```bash
UPLOAD_JSON="$(
  curl -fsS \
    -H 'Content-Type: application/octet-stream' \
    -X POST --data-binary @sample.bin \
    'http://localhost:8081/api/v1/blobs'
)"
BLOB_ID="$(jq -r '.blob.id' <<<"$UPLOAD_JSON")"
```

Success returns `201 Created` only after the manifest is persisted. `Location` points at the canonical blob URL and `ETag` contains the content key digest. Retrying identical bytes naturally returns the same content ID and reuses existing blocks.

Empty bodies return `400`. The configured maximum is enforced while streaming even if `Content-Length` is absent or dishonest. Authenticated uploads also consume a per-principal byte budget.

`Content-Length`, when present, is also treated as an exact declared size. Overflow stops the live stream and underflow fails at EOF before manifest publication. The runtime then inspects one prefix of at most 4 KiB. The packaged detector currently recognizes PDF's `%PDF-` signature:

- omitted `Content-Type` or `application/octet-stream` plus `%PDF-` selects PDF-aware chunking
- `application/pdf` plus a non-PDF signature returns `400`
- a concrete non-PDF claim plus `%PDF-` returns `400`
- formats without a registered detector use the configured default chunker

The probe is replayed exactly once into storage. The server never collects the request body to classify it.

The packaged server explicitly enables ZIO HTTP request streaming. Scala applications can use the [typed streaming SDK](../guide/scala-sdk.md), which selects known-length or chunked streaming bodies without collecting payload bytes.

### Session-localized upload

When the optional Shardcake runtime is enabled, send both typed headers:

```http
X-Graviton-Tenant-Id: 9f2f172c-8e6b-4aef-8be8-4c750420d971
X-Graviton-Upload-Session-Id: ab573594-abaa-44fa-867a-8c733bf87f6c
```

The tenant and session values are canonical lowercase UUIDs. The same pair resolves to one live owner, and the request body streams directly to that owner once. If security is enabled, the tenant must equal the authenticated JWT organization. Supplying locality headers to a server without the locality runtime returns `503 locality_unavailable`; the server never silently downgrades the request.

## Crash-safe resumable upload

Create a durable session with a client-generated canonical UUID. `Upload-Length` is optional, but when present it becomes an exact whole-object contract checked again at commit.

```bash
UPLOAD_ID='44444444-4444-4444-8444-444444444444'

curl -i -X POST \
  -H "X-Graviton-Upload-Session-Id: $UPLOAD_ID" \
  -H 'Upload-Length: 11' \
  -H 'Content-Type: application/octet-stream' \
  'http://localhost:8081/api/v1/uploads'
```

Success returns `201`, a JSON `ResumableUploadStatus`, `Location`, `Upload-Offset: 0`, `Upload-Expires`, and the upload ID. If the create response is lost, repeat the same request and then read the existing checkpoint.

Append one bounded part at the exact committed offset:

```bash
printf 'hello ' > /tmp/graviton-part-1
curl -i -X PATCH \
  -H 'Upload-Offset: 0' \
  -H 'Upload-Part-Id: 55555555-5555-4555-8555-555555555555' \
  -H 'Content-Length: 6' \
  --data-binary @/tmp/graviton-part-1 \
  "http://localhost:8081/api/v1/uploads/$UPLOAD_ID"
```

Success returns `204` and the new `Upload-Offset`. A part ID is an idempotency key within its session. Repeating an already applied part returns the durable offset without consuming the retry body. Reusing a new part ID with a stale offset returns `409 upload_offset_mismatch` plus the current `Upload-Offset`.

Recover state without moving bytes:

```http
GET  /api/v1/uploads/:id
HEAD /api/v1/uploads/:id
```

GET returns the full typed checkpoint. HEAD returns the same offset, length, expiry, and cache headers without a body. With security enabled, the session is scoped to the authenticated organization; otherwise `X-Graviton-Tenant-Id` may select an explicit tenant.

After every byte is durably staged, commit:

```bash
curl -fsS -X POST \
  "http://localhost:8081/api/v1/uploads/$UPLOAD_ID/commit" | jq .
```

Commit acquires an expiring durable lease, lazily streams staged objects in part order, and runs the result through the same MIME validation, PDF-aware chunker selection, hashing, deduplication, and manifest-last CAS publication as direct upload. The response state is `Committed`, `committedBlob` is the final content ID, and `Location` points to that blob. Repeating commit returns the same content ID. `DELETE /api/v1/uploads/:id` cancels an open session and cleans its staged objects.

Filesystem sessions and parts survive process restart. Shared S3 deployments persist session transitions transactionally in PostgreSQL and stage parts as S3 objects. Cleanup recovers both expired sessions and a committed session left between manifest publication and staging deletion.

## Inventory and pagination

```http
GET /api/v1/blobs?limit=100&cursor=<last-id>
```

`limit` must be between 1 and 1000. The response contains `blobs` and an optional `nextCursor`. Pass that cursor unchanged to retrieve the next page. Inventory is derived from persisted manifests, not process counters.

## Manifest metadata

```http
GET /api/v1/blobs/:id/metadata
```

The response includes the blob summary and exact persisted block references with content IDs, offsets, and sizes.

## Verify persisted bytes

```http
POST /api/v1/blobs/:id/verify
```

The server streams the stored blob through the selected hash implementation and compares the digest and byte count with the requested content ID.

## Retrieve

```http
GET /api/v1/blobs/:id
HEAD /api/v1/blobs/:id
```

A full response includes `Content-Length`, `ETag`, `Last-Modified`, `Accept-Ranges: bytes`, and immutable cache policy. HEAD returns the same status and metadata without a body.

### Byte ranges

One RFC-style byte range is supported:

```bash
curl -fsS \
  -H 'Range: bytes=1024-2047' \
  "http://localhost:8081/api/v1/blobs/$BLOB_ID" \
  --output part.bin
```

Open-ended and suffix forms such as `bytes=1024-` and `bytes=-512` are supported. A valid range returns `206` and `Content-Range`. Multiple or unsatisfiable ranges return `416`.

### Preconditions and caching

The server evaluates:

- `If-Match`
- `If-None-Match`
- `If-Modified-Since`
- `If-Unmodified-Since`
- `If-Range`

Matching `If-None-Match` returns `304` without a body. Failed write-style preconditions return `412`. Weak ETags are accepted for comparison. Content keys make successful blob representations immutable.

## Delete

```http
DELETE /api/v1/blobs/:id
```

Success returns `204`. Deletion removes the logical manifest. Shared blocks remain until a separate mark, quarantine, and purge lifecycle determines they are unreachable.

## Health and metrics

```http
GET /api/health/live
GET /api/health/ready
GET /api/stats
GET /metrics
```

Liveness reports the packaged build version and uptime. Readiness checks active backing services with a five-second timeout. Stats and metrics are process-local observations and are protected by `observability.read` when security is enabled.

## Error envelope

```json
{
  "error": "blob_not_found",
  "message": "Blob not found: sha-256:...:42"
}
```

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | `invalid_blob_id` | The path ID is not a valid content key |
| 400 | `invalid_blob` | Upload input violates an ingest constraint |
| 400 | `invalid_pagination` | `limit` or cursor input is invalid |
| 400 | `invalid_upload` | Resumable headers, part size, part count, or final declared size is invalid |
| 401 | `unauthenticated` | A valid bearer identity was not established |
| 403 | `forbidden` | Transport, origin, or capability policy denied the request |
| 404 | `blob_not_found` | The ID is valid but no manifest exists |
| 404 | `upload_not_found` | The tenant-scoped resumable session does not exist |
| 409 | `upload_exists` | A create request reused an existing session ID |
| 409 | `upload_offset_mismatch` | The part offset is stale; current offset is returned in a header |
| 409 | `upload_busy` / `upload_state` | Another live lease or terminal state prevents the transition |
| 410 | `upload_expired` | The durable session passed its expiry |
| 412 | response without body | A request precondition failed |
| 413 | `payload_too_large` | The streamed upload exceeded its configured maximum |
| 416 | `invalid_range` | The requested single range is malformed or unsatisfiable |
| 429 | `rate_limited` | A per-principal request or byte budget was exhausted |
| 503 | `locality_unavailable` | Typed locality headers were supplied to a server without the Shardcake runtime |
| 503 | `locality_failed` | Placement or the selected owner could not complete the one-pass stream |
| 500 | `inventory_failure` | Durable inventory could not be read |
| 500 | `storage_failure` | Metadata lookup, retrieval, or deletion failed |
| 500 | `verification_failure` | Persisted bytes could not be read and hashed |
| 500 | `ingest_failed` | The backing store could not complete the write |

Unexpected storage errors do not expose arbitrary exception messages.

## Executable proof

```bash
./sbt server/assembly
./scripts/smoke-packaged-server.sh
```

Contract tests cover resumable create, replay without body pull, stale offsets, checkpoint HEAD, commit, ranges, conditional reads, pagination, lifecycle behavior, streaming limits, preflights, origins, rate limits, and capability denial. The packaged smoke proves the ordinary blob contracts through a real network listener and fat JAR.

The SDK suite additionally proves a lazy logical 1 TiB request contract, a real 32 MiB direct lifecycle, and a real 6 MiB high-level resumable transfer split into three Iron-bounded parts over a socket. Runtime tests cover restart, TestClock expiry, interruption cleanup, incremental over-limit rejection, commit idempotency, post-commit crash cleanup, filesystem reconstruction, and a real PostgreSQL restart boundary. The 1 TiB case is structural evidence, not a physical 1 TiB transfer claim.
