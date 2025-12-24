# Quasar ↔ Cedar Compatibility Facade (Java-client contract)

## Status

**Status:** Draft  
**Created:** 2025-12-24  
**Updated:** 2025-12-24

## Summary

For Quasar integration, the legacy **Java client API** behavior is the real contract (not the SOAP/WSDL surface). A successful integration is the ability for Quasar to **impersonate the same verbs + quirks** so clients can swap the engine without breaking workflows.

This document captures:

- The minimal “parity surface” (verbs that must exist)
- The behavioral landmines (versioning/labels/repo/transforms)
- A migration strategy (facade first, engine swap later)

## Parity surface (must-support operations)

Your compatibility layer should cover the smallest set of operations that the client exercises:

- `addDoc(...)` (both `File` and `InputStream` variants)
- `updateDoc(...)` (versioning semantics + label movement)
- `getDoc(...)`
  - by `docId + repo`
  - advanced forms: by `versionId` / label
  - retrieval options: `toDrop` + `removeSignatures`
- `getDocHistory(...)`
- `search(query, repo)` (three query modes)
- `copyDoc(...)`
- `deleteDoc(...)`
- `getNewDocId()`

Treat this list as the façade’s compatibility spec: implement these atop Quasar, and you have an integration story.

## Data model implications (versioning + labels)

Legacy `updateDoc` is **not** “replace bytes”. It creates a **new version** and can optionally:

- Preserve old fields when new values are `null`
- Move a **label** from an older version onto the new one

Quasar therefore must be able to represent:

- **Document** (stable id)
- **DocumentVersion** (ordered list or DAG)
- **Version labels/tags** attached to versions
- **Label uniqueness per document** (a label cannot point to two versions of the same document)
- **Label moves** as a first-class operation (not “add another tag”)

If you can’t express “labels move”, you’ll break clients in subtle ways (they will observe two “CURRENT” versions or the wrong “latest”).

## Streaming + response shape (what must come back)

Legacy `addDoc/updateDoc` accept either a `File` or an `InputStream` and return a `DmsData`-shaped response.

For Quasar, the compatibility layer must:

- **Accept streaming bytes** (no buffering into `byte[]`)
- **Accept legacy metadata fields** (even if Quasar stores them in namespaces)
  - `courtAgency`, `courtType`, `courtLocation`, `caseNumber`, `cmsId`, `docType`, `description`, `securityLevel`
- **Return Cedar-shaped fields** even if Quasar is richer internally
  - doc id
  - mime/content-type
  - a “path” concept (derived compatibility field is fine)
  - success/errors semantics matching client expectations

> Practical note: even if Quasar’s “real API” is modern REST/Tapir, the compat layer must map these legacy fields into Quasar namespaces with schema URNs and provenance.

## Repo semantics (shortterm/longterm)

Every legacy call includes a `repo` value (often `shortterm` vs `longterm`). This must be preserved in the façade even if Quasar storage is unified.

Implementation mapping ideas:

- `repo` → placement / lane / sector / retention policy
- `repo` → default replication policy or storage class

If you ignore `repo`, you’ll violate workflow assumptions (clients often encode “hot vs archival” behavior into `repo`).

## Legacy quirks to emulate (or explicitly reject)

The façade should accept and ignore footguns where legacy clients expect them:

- `other` parameter (“future functionality, pass null”): accept and ignore

The façade should also implement (or explicitly return “unsupported”) transform-like retrieval flags:

- `toDrop` and `removeSignatures` imply **server-side transforms on retrieval**

Quasar-aligned approach:

- Model transforms as **Views, not copies**
- Treat `toDrop` / `removeSignatures` as *download options* that run a transient pipeline and optionally cache:
  - view key = deterministic function of `(base content hash, ops, versions)`

## Migration strategy (no flag day)

Build a compatibility façade that preserves the legacy URL and semantics while internally routing to Quasar + Graviton:

- **CedarCompatService**
  - same URL shape as legacy (e.g. `/dms/services/service?wsdl`) if required
  - internally routes to Quasar services:
    - upload session → Graviton ingest → DocumentVersion create
    - download → resolve DocumentVersion → stream bytes from Graviton
    - history → list versions
    - search → Quasar search backend, but emit Cedar-shaped results

This keeps clients alive while migrating them off SOAP (or off the legacy Java client) over time.

## “Tell it like it is”

Compatibility succeeds if you:

1. Implement the same verbs
2. Replicate version/label semantics
3. Preserve repo semantics
4. Stream bytes
5. Treat transforms as views

Everything else is negotiable.

