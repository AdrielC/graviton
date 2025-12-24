# Quasar HTTP API v1 (Draft)

Tenant-implicit, streaming-first document platform backed by Graviton CAS.

## Invariants

- **Tenant is implicit**: derived from deployment + JWT; never user-controlled via URL routing.
- **Document identity is stable**: `documentId` is a UUID (UUIDv7 recommended).
- **Content is immutable**: bytes live in Graviton CAS; updates create new versions.
- **Metadata is governed**: namespaced by URN, schema-bound by URN, versioned, auditable.
- **Derived metadata is not truth**: must be marked derived and include provenance.

## Auth

All endpoints require:

```http
Authorization: Bearer <JWT>
```

## Data model (conceptual)

### Document

- **documentId**: stable logical handle
- **currentVersionId**
- **externalRefs**: legacy/court/CMS identifiers
- **createdAt / updatedAt**

### DocumentVersion

Immutable snapshot:

- **versionId**
- **parents**: version DAG
- **contentRef**: blob or view
- **metadata**: namespaced bundle (see metadata docs)
- **permissionsSnapshot**
- **actor + reason**
- **createdAt**

### ContentRef

- **kind**: `blob | view`
- **key**: opaque identifier (blob key is CAS identity; view key is deterministic)

## Base path

All endpoints are under:

- `/v1`

## Documents & content

### Create a document upload session

```http
POST /v1/documents
Content-Type: application/json
```

Request:

```json
{
  "documentId": "optional-uuidv7",
  "placementHint": "transient | archival | legal_hold",
  "externalRefs": [
    { "system": "legacy", "value": "ABC123", "meta": { "repo": "shortterm" } }
  ],
  "initialMetadata": {
    "namespaces": {
      "urn:quasar:core": {
        "schema": "urn:quasar:schema:core@1.0.0",
        "id": "meta_01J...",
        "data": { "mimeType": "application/pdf" }
      }
    }
  },
  "initialPermissions": {
    "rules": [
      { "effect": "allow", "actions": ["doc.read"], "principals": ["role:clerk"] }
    ]
  }
}
```

Response:

```json
{ "uploadId": "uuid", "documentId": "uuid" }
```

Notes:

- **`placementHint`** replaces legacy “repo” in the clean API (tenant policy can override).
- If `documentId` is provided, treat this as an idempotent “create or resume upload”.

### Upload bytes (streaming)

```http
PUT /v1/uploads/{uploadId}/bytes
Content-Type: application/octet-stream
```

Response:

- `204 No Content`

### Finalize upload → create new version

```http
POST /v1/uploads/{uploadId}/finalize
Content-Type: application/json
```

Request:

```json
{ "labels": ["CURRENT"], "reason": "initial upload" }
```

Response:

```json
{
  "documentId": "uuid",
  "versionId": "uuid",
  "contentRef": { "kind": "blob", "key": "b3:..." }
}
```

### Get document

```http
GET /v1/documents/{documentId}
```

Response:

```json
{
  "documentId": "uuid",
  "currentVersionId": "uuid",
  "externalRefs": [],
  "createdAt": "2025-12-24T18:00:00Z",
  "updatedAt": "2025-12-24T18:05:00Z"
}
```

### List versions

```http
GET /v1/documents/{documentId}/versions
```

Response:

```json
{
  "versions": [
    { "versionId": "uuid", "parents": [], "labels": ["CURRENT"], "createdAt": "..." }
  ]
}
```

### Download content (current version)

```http
GET /v1/documents/{documentId}/content
```

Optional query params that produce **Views** (deterministic derived content):

- `view=urn:quasar:view:ocrText@1`
- `stripSignatures=true`
- `redactionProfile=public`

Server behavior:

- resolve base `contentRef` from current version
- compute `viewKey = f(base, ops, versions)`
- serve cached view if present, else run workflow to materialize, then stream

## Metadata

See:

- **[Quasar metadata governance](./quasar-metadata.md)** (namespaces, schema URNs, provenance, patching, validation lifecycle)

## Workflows & jobs

Workflows are the mechanism that produces derived metadata and/or materializes views.

### Run a workflow against a document

```http
POST /v1/documents/{documentId}/workflows/{workflowUrn}/run
Content-Type: application/json
```

Request:

```json
{
  "inputs": {
    "sourceVersion": "current",
    "targetNamespace": "urn:quasar:ocr",
    "options": { "language": "en" }
  }
}
```

Response:

```json
{ "jobId": "uuid" }
```

### Get job status

```http
GET /v1/jobs/{jobId}
```

Response:

```json
{
  "jobId": "uuid",
  "state": "pending | running | completed | failed | dead",
  "startedAt": "...",
  "finishedAt": "...",
  "error": null,
  "outputs": {
    "derivedNamespaces": ["urn:quasar:ocr"],
    "viewKeys": ["v1:..."]
  }
}
```

## Legacy integration

See:

- **[Legacy repository integration](./legacy-repos.md)** (read-through + import-on-read)

