# Quasar Metadata Envelope v1.1

## Status

**Status:** Draft (authoritative direction)  
**Updated:** 2025-12-27

## Summary

Quasar Metadata Envelope v1.1 defines a **portable**, **schema-pinned**, and **safe-by-default** JSON wrapper for document metadata:

- **Namespace identifiers are IRIs** (URNs or URLs) → no collisions by construction
- **Schemas are identified by IRIs** → deterministic meaning over time
- **Stored metadata always pins the schema identity** used for validation
- **Invalid/unknown payloads are preserved** but must be marked **quarantined**
- **Typed decoding is only allowed when** `status == "valid"`

This spec is about deterministic meaning and governance. It is **not** JSON-LD.

## Canonical JSON shape

```json
{
  "system": {
    "envelope": "urn:quasar:meta-envelope:v1.1",
    "createdAt": "2025-12-27T16:12:33Z",
    "createdBy": { "principal": "oidc:sub:abc123" },
    "updatedAt": "2025-12-27T16:12:33Z",
    "source": {
      "ingest": "upload",
      "requestId": "uuidv7:018c3b5a-..."
    }
  },
  "namespaces": {
    "urn:quasar:ns:upload": {
      "schema": { "$id": "urn:quasar:schema:upload:v1" },
      "status": "valid",
      "data": {
        "originalFileName": "foo.pdf",
        "contentType": "application/pdf"
      }
    }
  }
}
```

## Required fields (hard requirements)

- `system` **MUST** exist and **MUST** be an object
- `system.envelope` **MUST** be an IRI string identifying this envelope version
- `system.createdAt` **MUST** be RFC 3339 (`date-time`)
- `system.createdBy.principal` **MUST** be a non-empty string
- `system.updatedAt` **MUST** be RFC 3339 (`date-time`)
- `system.source.requestId` **MUST** be a non-empty string (prefer UUIDv7)
- `namespaces` **MUST** exist and **MUST** be an object (may be empty)

## IRI rules

### Namespace keys

Each key under `namespaces` **MUST** be a valid IRI string.

Recommended examples:

- URN style: `urn:quasar:ns:upload`, `urn:cedar:ns:legal-case`
- URL style: `https://schema.quasar.io/ns/upload`

### Schema identifiers

Each namespace entry binds to a schema via:

```json
{ "schema": { "$id": "urn:quasar:schema:upload:v1" } }
```

- `schema.$id` **MUST** be an IRI string when present
- schema identifiers should be immutable (new version ⇒ new IRI)

## Namespace entry shape

Each namespace entry **MUST** be an object with:

- `data` (required): JSON object
- `status` (required): enum
- `schema` (optional on ingest; **required on storage**)

### Status enum

`status` **MUST** be one of:

- `valid`: data conforms to pinned schema
- `quarantined`: data does not conform, schema is unknown/unavailable, or policy requires quarantine
- `unverified`: transient ingestion-only state (not allowed in stored envelopes)

**Typed decoding rule (hard invariant):** consumers **MUST only** decode `data` into typed structures if `status == "valid"`.

### Errors (diagnostics)

When `status == "quarantined"`, an entry **SHOULD** include:

```json
{
  "errors": [
    { "path": "/field", "code": "required", "message": "..." }
  ]
}
```

Diagnostics are not “truth”; truth is the `(status, pinned schema)`.

## Schema resolution + pinning

Quasar may accept ergonomic client payloads that omit `schema.$id` on ingest, but **storage must be deterministic**:

- If `schema.$id` is omitted, Quasar **resolves** a default schema from the Schema Registry for that namespace.
- Quasar **validates** against the resolved (or provided) schema.
- Quasar **MUST persist** the pinned schema identity used for validation.

Implementation note: you can satisfy “pinned and queryable” either by:

- writing `schema.$id` back into the stored JSON envelope, or
- storing an internal FK plus a durable schema IRI in a registry row (queryable via join).

## Quarantine policy (default)

On validation failure (or unknown schema), Quasar **MUST** store:

- `status = "quarantined"`
- `schema.$id` set to the pinned/attempted schema IRI (even if validation failed)
- `errors` populated when feasible (bounded)

Quarantine preserves bytes while preventing unsafe typed decoding.

## Policy 3 (optional): untyped raw capture namespace

Optionally, when an entry is quarantined, Quasar may also copy the raw payload into a dedicated “raw” namespace (explicitly configured), e.g.:

- `urn:quasar:ns:untyped`
- `urn:quasar:ns:vendor-raw:thomsonreuters`

This is an escape hatch, not a substitute for schema governance.

## Envelope JSON Schemas (self-validating)

Two JSON Schemas are provided:

- **Ingest form** (schema may be omitted):
  - `docs/public/schemas/quasar-metadata-envelope-v1.1.ingest.schema.json`
- **Stored form** (schema is required; `unverified` forbidden):
  - `docs/public/schemas/quasar-metadata-envelope-v1.1.stored.schema.json`

## Relationship to Postgres “truth”

This envelope is portable, but Quasar’s enforcement should still live in columns where it matters. The current repo DDL already separates:

- document/version identity + timestamps: `quasar.document`, `quasar.document_version`
- namespace registry: `quasar.namespace` (`urn` as text)
- schema registry: `quasar.schema_registry` (`schema_urn`, `schema_json`, `canonical_hash`, status)
- per-version namespace payloads + validation result: `quasar.document_namespace` (`data`, `is_valid`, `validation_errors`, `schema_id`)

Mapping note (current DDL):

- `status = "valid"` ↔ `is_valid = true`
- `status = "quarantined"` ↔ `is_valid = false` + `validation_errors` populated where feasible
- `status = "unverified"` is an ingestion-only state and should not be persisted

If `system` and columns conflict, **columns win**.
