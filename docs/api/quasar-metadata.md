# Quasar metadata governance (Draft)

Metadata in Quasar is **namespaced**, **schema-bound**, and **auditable**. This is the boundary where “AI output” must not launder itself into truth.

## Core rules

- **Namespaces are ownership boundaries**: a namespace URN keys a metadata entry.
- **Schemas are explicit**: each entry binds to a schema URN (with semver).
- **Entries are versioned**: every accepted write yields a new metadata entry id.
- **Canonical vs derived is enforced**:
  - canonical: synchronous validation; invalid → `422`
  - derived: provenance required; can be async validated; invalid may be quarantined

## Envelope v1.1 (portable + schema-pinned)

This page describes the existing “namespaces bundle” model used throughout the Quasar docs. The **authoritative direction** going forward is the **Quasar Metadata Envelope v1.1**, which adds:

- a required `system` receipt block (timestamps, principal, request correlation)
- explicit schema pinning via `schema: { "$id": "<schema-iri>" }`
- an explicit `status` (`valid | quarantined | unverified`) with a hard typed-decoding rule

See `design/quasar-metadata-envelope-v1.1` for the full spec and JSON Schemas.

## Storage shape

The metadata snapshot on a `DocumentVersion` is a single JSON object:

```json
{
  "namespaces": {
    "urn:quasar:core": {
      "schema": "urn:quasar:schema:core@1.0.0",
      "id": "meta_01J...",
      "data": {
        "mimeType": "application/pdf",
        "sizeBytes": 123456,
        "ingest": { "source": "upload", "receivedAt": "2025-12-24T18:00:00Z" }
      }
    },
    "urn:quasar:case": {
      "schema": "urn:quasar:schema:case@1.2.0",
      "id": "meta_01K...",
      "data": {
        "caseNumber": "CV-2024-123",
        "courtLocation": "Washoe"
      }
    }
  }
}
```

Enforcement notes:

- Namespace keys **must** be URNs.
- `data` **must** be an object (no scalar/array roots).

## Canonical vs derived

Each namespace write is one of:

- **canonical**: system-of-record meaning (can affect security/workflows/retention/search)
- **derived**: OCR text, classifications, entities, summaries, confidence scores

### Provenance (required for derived)

Derived metadata must include:

- **producer**: plugin/model name + version
- **producedAt**
- **input**: contentRef (blob or view) that was used to derive the output
- **confidence**: `0..1` where applicable

## API: read metadata

### Get metadata snapshot (current version)

```http
GET /v1/documents/{documentId}/metadata
```

Returns the metadata bundle (the namespaces map).

### Get one namespace entry (current version)

```http
GET /v1/documents/{documentId}/metadata/{namespaceUrn}
```

Response:

```json
{
  "namespaceUrn": "urn:quasar:case",
  "entry": {
    "id": "meta_01K...",
    "schema": "urn:quasar:schema:case@1.2.0",
    "data": { "caseNumber": "CV-2024-123", "courtLocation": "Washoe" }
  }
}
```

## API: write metadata (replace semantics)

### Write namespace entries (canonical or derived)

```http
POST /v1/documents/{documentId}/metadata
Content-Type: application/json
```

Request:

```json
{
  "mode": "canonical | derived",
  "bundle": {
    "namespaces": {
      "urn:quasar:entities": {
        "schema": "urn:quasar:schema:entities@1.0.0",
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

Response possibilities:

- canonical accepted (creates a new DocumentVersion):

```json
{ "status": "accepted", "versionId": "uuid" }
```

- derived accepted async:

```json
{ "status": "accepted", "jobId": "uuid" }
```

- canonical rejected:

```json
{
  "status": "rejected",
  "error": { "code": "VALIDATION_FAILED", "message": "..." }
}
```

Enforcement:

- caller must have **`meta.write:<namespaceUrn>`**
- canonical writes validate **synchronously** against schema URN
- derived writes require provenance (reject if missing)

## Patch-based metadata (recommended)

Patching is the safest “day-to-day” mutation mechanism: it is explicit, audit-friendly, and supports optimistic concurrency.

### Semantics

- Patch **one namespace entry**, not the whole bundle.
- Use **RFC 6902 JSON Patch** (not merge patch) for canonical metadata.
- Patch application is **append-only**:
  - new metadata entry id is minted
  - canonical patches create a new DocumentVersion
  - patch ops are stored as immutable audit artifacts

### Guardrails

- Namespace ownership: caller must have **`meta.patch:<namespaceUrn>`**
- Patch cannot change `schema` (schema changes happen via `/migrate`)
- Canonical patches validate the *resulting* `data` synchronously
- Limits: op count and payload size are bounded

### Patch endpoint

```http
POST /v1/documents/{documentId}/metadata/{namespaceUrn}/patch
Content-Type: application/json
```

Request (canonical):

```json
{
  "mode": "canonical",
  "baseMetadataId": "meta_01K...",
  "patch": [
    { "op": "test", "path": "/caseNumber", "value": "CV-2024-123" },
    { "op": "replace", "path": "/courtLocation", "value": "Clark" }
  ],
  "reason": "corrected court location"
}
```

Response:

```json
{
  "status": "accepted",
  "versionId": "uuid",
  "newMetadataId": "meta_01K_NEW..."
}
```

If `baseMetadataId` does not match the current entry:

- return `409 Conflict` with the current metadata id (optimistic concurrency).

## Schema registry & migrations

### Register schema

```http
POST /v1/schemas
Content-Type: application/json
```

```json
{
  "schemaUrn": "urn:quasar:schema:case@1.2.0",
  "namespaceUrn": "urn:quasar:case",
  "lifecycle": "published",
  "jsonSchema": {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "properties": {}
  }
}
```

### Register migration (explicit + invertible)

```http
POST /v1/migrations
Content-Type: application/json
```

Rule: if the forward transform would lose information and the inverse cannot restore it, reject registration.

