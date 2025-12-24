# Legacy Cedar integration

This page documents the compatibility surface for Cedar 3 repositories on disk.

## Read-through (implemented)

If a deployment has Cedar repositories on disk (repo root with `metadata/` + `binaries/`), Quasar/Graviton can serve bytes directly without importing into CAS.

### Endpoint

```http
GET /legacy/{repo}/{docId}
```

Behavior:

- `200 OK` with a streamed response body from the Cedar filesystem
- `404 Not Found` if Cedar metadata is missing or the binary is missing
- `422 Unprocessable Entity` if metadata exists but is invalid (e.g. missing/invalid binary hash)
- `409 Conflict` if the binary or metadata exists but is unreadable (permissions/corruption)

Notes:

- This endpoint is **streaming-only** (no full-file buffering).
- The Cedar binary path resolver attempts a small set of common hash-partitioning layouts and falls back to a lazy index.

## Import-on-read (planned)

Import-on-read is the preferred end state: first access migrates bytes into Graviton CAS; subsequent reads come from Quasar document endpoints.

### Endpoint (proposed)

```http
POST /v1/legacy/cedar/import
Content-Type: application/json
```

Request:

```json
{ "legacyRepo": "shortterm", "legacyDocId": "ABC123", "mode": "import-on-read | bulk" }
```

Response:

```json
{ "jobId": "uuid", "documentId": "uuid" }
```

Internals:

- resolve Cedar XML metadata → legacy descriptor
- stream Cedar binary → Graviton ingest (hashing/chunking streaming)
- create Quasar `Document` + `DocumentVersion`
- store mappings:
  - `(legacyRepo, legacyDocId) -> documentId`
  - `(legacyRepo, legacyBinaryHash) -> blobKey` (dedupe across docs)

Idempotency:

- safe to retry; no duplicate documents for the same `(legacyRepo, legacyDocId)`.

