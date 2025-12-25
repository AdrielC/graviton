# Quasar HTTP API v1 (Draft)

## Status

**Status:** Draft  
**Created:** 2025-12-24  
**Updated:** 2025-12-24

## Summary

Quasar is a **tenant-implicit, streaming-first, CAS-backed** document platform.

This is a draft API design that assumes:

- **Tenant is implicit** (derived from deployment + JWT); it is not a URL routing boundary.
- **Content is immutable** (stored in Graviton CAS); changes create versions.
- **Metadata is namespaced + schema-governed + auditable**, with provenance that prevents “derived” data from laundering into “truth”.
- **Transforms are views**: derived outputs are deterministic `ContentRef(view)`s, not duplicated files.

Base path: `/v1`

## Identity & authorization

### Authentication

- `Authorization: Bearer <JWT>`

### Tenant context

- Derived from JWT claims (e.g. `iss`, `aud`, optional `tenant_id`) and deployment config.
- Not user-controllable via path params.

### Authorization (conceptual)

- Deny-first.
- Enforce at least:
  - `doc.read`, `doc.write`
  - `meta.write:<namespaceUrn>` (or `meta.patch:<namespaceUrn>`)
  - `workflow.run:<workflowUrn>`
- “CedarAI” features may only restrict access further, never widen it.

## Data model

### Document

Stable logical handle:

- `documentId` (UUIDv7 recommended)
- `currentVersionId`
- `externalRefs[]` (legacy IDs, CMS IDs, etc.)
- timestamps

### DocumentVersion

Immutable snapshot:

- `versionId`
- `parents[]` (DAG)
- `contentRef` (`blob` or `view`)
- `metadataBundle` (namespaced)
- `permissionsSnapshot`
- `actor + reason`
- timestamps

### ContentRef

- `kind`: `blob | view`
- `key`: opaque string
  - `blob` key = CAS identity of raw bytes
  - `view` key = deterministic identity derived from `(base, ops, versions)`

For the determinism/audit rules behind views (including selector-driven views and frozen input snapshots), see **[ContentRef, Views, Selectors, and Frozen Snapshots](./quasar-contentref-views-selectors.md)**.

## Metadata model (namespaces + schema URNs)

Metadata is a map keyed by namespace URN. Each namespace entry includes a schema URN, a stable entry id, and an object-shaped payload:

```json
{
  "namespaces": {
    "urn:tybera:quasar:core": {
      "schema": "urn:tybera:schema:quasar-core@1.0.0",
      "id": "meta_01J...",
      "data": {
        "mimeType": "application/pdf",
        "sizeBytes": 123456,
        "ingest": { "source": "upload", "receivedAt": "2025-12-24T18:00:00Z" }
      }
    }
  }
}
```

Rules:

- Namespace URNs prevent collisions.
- Schema URNs (with semver) make meaning explicit and migratable.
- `data` must be a JSON object (no scalar/array roots).

### Canonical vs derived

Each metadata write is either:

- **canonical**: system-of-record meaning (can affect security/workflows/retention)
- **derived**: OCR, classification, entities, summaries, confidence scores

Derived metadata must include provenance:

- producer (plugin/model + version)
- producedAt
- input contentRef
- optional confidence (0–1)

### Validation lifecycle

- **Canonical**: validate synchronously; reject invalid with `422`.
- **Derived**: may validate async; invalid can be quarantined and excluded from search/workflows by default.

## API: documents & content

### Create a document upload session

`POST /v1/documents`

Use this for:

- server-generated IDs, or
- caller-supplied UUIDv7 (upsert semantics by `documentId`)

Request:

```json
{
  "documentId": "optional-uuidv7",
  "placementHint": "transient | archival | legal_hold",
  "externalRefs": [
    { "system": "cedar3", "value": "ABC123", "meta": { "repo": "shortterm" } }
  ],
  "initialMetadata": {
    "namespaces": {
      "urn:tybera:quasar:core": {
        "schema": "urn:tybera:schema:quasar-core@1.0.0",
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

- `placementHint` is a policy hint (not a resource identity); tenant policy may override it.

### Upload bytes (streaming)

`PUT /v1/uploads/{uploadId}/bytes`

- `Content-Type: application/octet-stream`
- streaming + backpressure; do not buffer entire payload

Response: `204 No Content`

### Finalize upload → create a new version

`POST /v1/uploads/{uploadId}/finalize`

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

`GET /v1/documents/{documentId}`

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

`GET /v1/documents/{documentId}/versions`

```json
{
  "versions": [
    { "versionId": "uuid", "parents": [], "labels": ["CURRENT"], "createdAt": "..." }
  ]
}
```

### Download content (current version)

`GET /v1/documents/{documentId}/content`

Optional query params that produce views:

- `view=urn:tybera:view:ocrText@1`
- `stripSignatures=true`
- `redactionProfile=public`

Server behavior:

- resolve base contentRef from current version
- compute view key = f(base, ops, versions)
- return cached if present; otherwise run a workflow to materialize, then stream

## API: metadata (snapshot writes)

### Get metadata snapshot (current version)

`GET /v1/documents/{documentId}/metadata`

Returns a `MetadataBundle` (`namespaces` map).

### Write metadata (creates version for canonical)

`POST /v1/documents/{documentId}/metadata`

Request:

```json
{
  "mode": "canonical | derived",
  "bundle": {
    "namespaces": {
      "urn:tybera:cedar:entities": {
        "schema": "urn:tybera:schema:cedar-entities@1.0.0",
        "id": "meta_01M...",
        "data": { "people": ["Jane Doe"], "orgs": ["Acme LLC"] }
      }
    }
  },
  "provenance": {
    "producer": { "name": "ner-plugin", "version": "0.3.2" },
    "producedAt": "2025-12-24T19:00:00Z",
    "input": { "kind": "blob", "key": "b3:..." },
    "confidence": 0.81
  }
}
```

Responses:

- canonical accepted:

```json
{ "status": "accepted", "versionId": "uuid" }
```

- derived accepted async:

```json
{ "status": "accepted", "jobId": "uuid" }
```

- canonical invalid:

```json
{ "status": "rejected", "error": { "code": "VALIDATION_FAILED", "message": "..." } }
```

Enforcement:

- caller must have `meta.write:<namespaceUrn>`
- canonical must validate synchronously against the schema URN
- derived writes require provenance

> Patch-based mutation is specified separately: see **[Patch-based metadata](./quasar-metadata-patching.md)**.

## API: schema registry & governance

### Register schema

`POST /v1/schemas`

```json
{
  "schemaUrn": "urn:tybera:schema:cedar-case@1.2.0",
  "namespaceUrn": "urn:tybera:cedar:case",
  "lifecycle": "published",
  "jsonSchema": {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "properties": {}
  }
}
```

```json
{ "status": "created", "canonicalHash": "sha256:..." }
```

### Fetch schema

`GET /v1/schemas/{schemaUrn}`

## API: migrations (explicit, invertible)

### Register migration

`POST /v1/migrations`

```json
{
  "fromSchemaUrn": "urn:tybera:schema:cedar-case@1.1.0",
  "toSchemaUrn": "urn:tybera:schema:cedar-case@1.2.0",
  "forwardTransform": { "type": "jsonpatch", "ops": [] },
  "inverseTransform": { "type": "jsonpatch", "ops": [] }
}
```

Rule: if forward would lose info and inverse can’t restore, reject registration.

### Apply migration (creates new version)

`POST /v1/documents/{documentId}/migrate`

```json
{
  "namespaceUrn": "urn:tybera:cedar:case",
  "toSchemaUrn": "urn:tybera:schema:cedar-case@1.2.0",
  "reason": "court schema update"
}
```

```json
{ "status": "accepted", "jobId": "uuid" }
```

On completion, create a new `DocumentVersion` (never rewrite old versions).

## API: search

`POST /v1/search`

```json
{
  "query": { "mode": "fts | vector | plain", "text": "motion to dismiss" },
  "filters": {
    "orgId": "washoe",
    "namespace": "urn:tybera:cedar:case",
    "docType": "motion"
  },
  "limit": 50,
  "cursor": null
}
```

Search must:

- enforce permissions
- ignore quarantined derived metadata by default
- return snippets + document/version IDs

## API: workflows (CedarAI lives here)

### Run workflow against a document

`POST /v1/documents/{documentId}/workflows/{workflowUrn}/run`

Examples (URNs are illustrative):

- `urn:tybera:cedar:workflow:ocr@1`
- `urn:tybera:cedar:workflow:classify@1`
- `urn:tybera:cedar:workflow:redact@1`

```json
{
  "inputs": {
    "sourceVersion": "current",
    "targetNamespace": "urn:tybera:cedar:ocr",
    "options": { "language": "en" }
  }
}
```

```json
{ "jobId": "uuid" }
```

Workflow output rules:

- derived metadata requires provenance
- materialized views produce deterministic `ContentRef(view)`

## API: jobs

### Get job status

`GET /v1/jobs/{jobId}`

```json
{
  "jobId": "uuid",
  "state": "pending | running | completed | failed | dead",
  "startedAt": "...",
  "finishedAt": "...",
  "error": null,
  "outputs": {
    "derivedNamespaces": ["urn:tybera:cedar:ocr"],
    "viewKeys": ["v1:..."]
  }
}
```

## API: legacy Cedar integration (compat, not core)

### Import legacy doc (repo + legacyDocId)

`POST /v1/legacy/cedar/import`

```json
{ "legacyRepo": "shortterm", "legacyDocId": "ABC123", "mode": "import-on-read | bulk" }
```

```json
{ "jobId": "uuid", "documentId": "uuid" }
```

Internals (conceptual):

- resolve legacy metadata descriptor
- stream legacy binary → Graviton ingest
- create Document + Version
- store mapping `(legacyRepo, legacyDocId) -> documentId`

## Does “repo” matter?

Yes, but it should be a policy detail, not a resource identity.

- Clean API: hide behind `placementHint` (or omit entirely).
- Legacy façade: accept `repo` and translate `shortterm/longterm` → placement/retention policies.

