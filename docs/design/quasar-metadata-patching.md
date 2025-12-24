# Patch-based Metadata (Namespace-scoped RFC 6902)

## Status

**Status:** Draft  
**Created:** 2025-12-24  
**Updated:** 2025-12-24

## Summary

Snapshot “replace the whole namespace payload” writes are easy to implement, but they create governance problems:

- accidental truth drift (unrelated fields overwritten)
- huge audits with unclear intent
- poor concurrency behavior (“last write wins”)

Quasar should support **patch-based metadata mutation**:

- patches are applied to a *single namespace entry* (not the whole metadata bundle)
- patches use **RFC 6902 JSON Patch**
- patches are **append-only events** (auditable)
- schema upgrades happen via explicit migrations (not ad-hoc patch drift)

## Core rules

### 1) Patch a namespace entry, not the whole metadata blob

Metadata is:

- `namespaces: Map[namespaceUrn -> entry]`

A patch targets exactly one namespace URN and (for concurrency) a specific base `metadataId`.

### 2) Use RFC 6902 JSON Patch

Supported ops: `add`, `remove`, `replace`, `move`, `copy`, `test`.

Avoid RFC 7386 “merge patch” for canonical metadata: it is too easy to delete fields unintentionally.

### 3) Patches are append-only events

Applying a patch creates:

- a new `metadataId` (even if the schema is unchanged)
- a new `DocumentVersion` **for canonical metadata** (recommended)

Store the patch as an immutable audit artifact.

### 4) Guardrails (non-negotiable)

- **Namespace ownership**: caller must have `meta.patch:<namespaceUrn>`
- **Schema immutability under patch**: patch cannot change `entry.schema`
  - schema changes go through `/migrate` (invertible)
- **Canonical vs derived**:
  - canonical: synchronous validation of resulting `data` against schema URN; reject with `422`
  - derived: provenance required; async validation + quarantine allowed
- **Limits**: enforce patch size and op-count limits to prevent abuse

## Endpoints

### Get a namespace entry

`GET /v1/documents/{documentId}/metadata/{namespaceUrn}`

```json
{
  "namespaceUrn": "urn:quasar:cedar:case",
  "entry": {
    "id": "meta_01K...",
    "schema": "urn:quasar:schema:cedar-case@1.2.0",
    "data": { "caseNumber": "CV-2024-123", "courtLocation": "Washoe" }
  }
}
```

### Patch a namespace entry

`POST /v1/documents/{documentId}/metadata/{namespaceUrn}/patch`

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

Request (derived, provenance required):

```json
{
  "mode": "derived",
  "baseMetadataId": "meta_01M...",
  "patch": [
    { "op": "add", "path": "/entities/people/-", "value": "Jane Doe" }
  ],
  "provenance": {
    "producer": { "name": "ner-plugin", "version": "0.3.2" },
    "producedAt": "2025-12-24T19:00:00Z",
    "input": { "kind": "blob", "key": "b3:..." },
    "confidence": 0.81
  }
}
```

Possible conflict response (optimistic concurrency):

- If `baseMetadataId` does not match the current namespace entry id, return `409 CONFLICT` and include the current `metadataId`.

## Storage model (recommended)

Two complementary storage shapes:

### A) Version snapshots store full metadata

Each `DocumentVersion` stores the full metadata bundle snapshot (materialized state).

### B) Patch history stored separately (audit/event log)

`metadata_patches`:

- `patch_id` (UUIDv7)
- `document_id`
- `version_id_created` (nullable for async derived flows)
- `namespace_urn`
- `base_metadata_id`
- `new_metadata_id`
- `patch_ops` (JSONB list, RFC 6902)
- `mode` (`canonical | derived`)
- `actor`
- `provenance` (required for derived)
- timestamps

This gives:

- precise diffs and audit intent
- easy replay/debugging
- smaller mutation writes (even if you still materialize snapshots at commit)

## Strong opinion: no schema drift by patch

If patches can modify `entry.schema`, schema governance becomes decorative and the system accumulates silent meaning drift.

Make schema changes explicit and invertible via `/v1/migrations` + `/v1/documents/{documentId}/migrate`.

