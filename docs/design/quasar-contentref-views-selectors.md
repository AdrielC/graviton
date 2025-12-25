# ContentRef, Views, Selectors, and Frozen Snapshots (Quasar workflow determinism)
 
## Status
 
**Status:** Accepted  
**Created:** 2025-12-25  
**Updated:** 2025-12-25
 
## Summary
 
Quasar must support long-running, multi-step workflows over immutable content with AI + human decision points, while remaining crash-safe, replayable, and audit-defensible years later.
 
The governing question is:
 
> “What did the system see, what did it do, and why did it do it — at that time?”
 
This document locks in the primitives and determinism rules needed to answer that question without “hindsight recomputation”.
 
## The core problem
 
Workflows involve:
 
- immutable content (PDFs, images, etc.)
- derived artifacts (OCR, renders, merges, extraction)
- AI analysis + human review
- pause/resume and crash safety
- replayability and audit defensibility
 
At any point, the system must be able to reconstruct *exactly* what inputs were used and *exactly* what was produced, without re-running selection logic against a drifting database.
 
## Primitives
 
### ContentRef (indirection over “something that resolves to bytes”)
 
`ContentRef` is the universal handle for “something that can be resolved to bytes or logic that produces bytes.”
 
A `ContentRef` is one of:
 
- **BlobRef**: points to materialized immutable bytes (CAS identity)
- **ViewRef**: points to a derivation recipe which itself resolves (eventually) to materialized bytes
 
**ContentRef MUST NOT encode**:
 
- storage locators/paths/URLs
- blocks/chunks/layout details
- document IDs, database row IDs, permissions, workflow state
 
This separation is intentional: content identity and derivation are not allowed to accidentally couple to metadata, access control, or storage topology.
 
### Blob (materialized bytes)
 
A Blob is:
 
- immutable
- content-addressed (hash algorithm + digest + size)
- potentially stored in multiple *representations* (mono/chunked, replicated, hot/cold tiers)
 
**Blob identity MUST NOT encode** storage mode, location, tier, or physical layout. Those are representations, not identity.
 
### View (derived content)
 
A View is:
 
- a deterministic recipe
- applied to one or more `ContentRef` inputs
- producing an output Blob
- immutable once defined
 
Views form a **DAG** (not necessarily a tree). Resolution must always terminate at materialized bytes:
 
`ViewRef → inputs (ContentRefs) → … → BlobRef(s)`
 
## Why Views exist (the real reason)
 
Views are not just caching.
 
Views exist because they are how machines explain themselves to humans:
 
- freeze **what was seen**
- freeze **what was used**
- freeze **how it was derived**
 
This enables:
 
- human review (“review the view”, not live computation)
- pause/resume without ephemeral state
- crash safety (“no recomputation unless explicitly requested”)
- audit replay and defensibility (“no hindsight recomputation”)
 
## Selectors, snapshots, and determinism
 
### The problem
 
Some derivations depend on **selection logic** rather than explicit blob inputs, e.g.:
 
- “merge all PDFs in a case up to time T”
- “build a binder from the latest accepted filings”
- “summarize all orders issued so far”
 
If selection is re-run later, results can drift due to:
 
- backfills, schema changes, query changes
- ambiguous ordering
- operational realities
 
For court-grade systems, that drift is unacceptable.
 
### The solution: three layers
 
#### (1) SelectorURN — the semantic name (versioned intentionally)
 
SelectorURN names the *rule*, not a particular SQL string.
 
Example:
 
- `urn:tybera:selector:build-binder:v2`
 
SelectorURN MUST be:
 
- stable and human-meaningful
- versioned intentionally (breaking changes require a new version)
 
#### (2) SelectorSpec (SelectorRule invocation) — provenance, not identity
 
SelectorSpec explains how the selector was applied:
 
- which selector version
- which params
- which visibility cutoff
- which ordering semantics
 
Example (illustrative):
 
```json
{
  "selectorUrn": "urn:tybera:selector:build-binder:v2",
  "params": { "caseId": "CASE-123" },
  "cutoffIngestSeq": 482901,
  "orderBy": ["inserted_at", "document_version_id"]
}
```
 
SelectorSpec is used for **explainability and provenance**. It is not relied on to guarantee determinism.
 
#### (3) SelectionSnapshot — the frozen truth (the actual input)
 
The selector is executed once. The resulting **ordered list of inputs** is frozen into an immutable, content-addressed blob: a **SelectionSnapshot**.
 
Example (illustrative):
 
```json
{
  "schema": "urn:tybera:schema:selection-snapshot@1",
  "selectorUrn": "urn:tybera:selector:build-binder:v2",
  "cutoffIngestSeq": 482901,
  "orderBy": ["inserted_at", "document_version_id"],
  "inputs": [
    { "docVersionId": "…", "contentRef": { "kind": "blob", "key": "…" } },
    { "docVersionId": "…", "contentRef": { "kind": "view", "key": "…" } }
  ],
  "provenance": {
    "appSha": "…",
    "schemaVersion": "V17"
  }
}
```
 
**Rules:**
 
- Views MUST depend on SelectionSnapshot blobs, not on re-running queries.
- SelectionSnapshots MUST preserve an explicit, deterministic order for downstream order-sensitive transforms.
- SelectionSnapshots are immutable and content-addressed; they become ordinary `ContentRef(blob)` inputs to views.
 
**Result:** Specs explain; snapshots guarantee.
 
## Canonical JSON format for SelectionSnapshot
 
SelectionSnapshot content is hashed (directly or indirectly), so it MUST have a deterministic canonical encoding.
 
**Decision:** SelectionSnapshot JSON MUST be canonicalized using **RFC 8785 (JSON Canonicalization Scheme, JCS)** before hashing/storing as the canonical snapshot bytes.
 
Practical implications:
 
- object keys are lexicographically sorted
- no insignificant whitespace
- numbers use canonical forms
- strings use standard JSON escaping
- arrays preserve order (order is semantically meaningful here)
 
If a snapshot includes fields not required for determinism (e.g. extra debugging info), they MUST either:
 
- be placed under a non-hashed “debug” envelope that is not part of the canonical content, or
- be excluded entirely from the canonical representation
 
## ViewKey construction (identity rules)
 
View identity MUST depend only on deterministic inputs:
 
- **transform URN** (or name/scope)
- **normalized params** (stable forms; no timestamps/“now”/random seeds)
- **ordered input ContentRefs**, including any SelectionSnapshot `ContentRef(blob)`
 
### Mapping to current Graviton view key derivation
 
Graviton currently derives view keys from a single “base key” plus canonical transform bytes. To represent multi-input views and selector-driven views without changing the core rule:
 
- The ordered list of view inputs MUST be represented as a deterministic **context blob** (often the SelectionSnapshot itself).
- The view’s “base key” is then the key of that context blob/manifest.
- The transform canonical bytes include the transform identity and normalized args.
 
This preserves the invariant: **view keys are deterministic and depend only on content + transform**.
 
## Order sensitivity
 
Transforms MUST declare whether they are:
 
- **order-sensitive**: input order changes output (PDF merge, concatenation, binder assembly)
- **order-insensitive**: input order does not change output (per-document OCR, per-document classification)
 
Rules:
 
- For order-sensitive transforms, the SelectionSnapshot input order is authoritative.
- For order-insensitive transforms operating over a set, the system MUST still impose a deterministic order for hashing/explanation (e.g. sort by `(contentRef.kind, contentRef.key)` or by explicit `orderBy` from the snapshot).
 
## Agents, humans, and pause/resume
 
Agents (e.g. Golem) are used for orchestration and durable workflow progress:
 
- consume `ContentRef`s (blobs + views)
- produce new views
- pause at decision points
- resume when humans act
 
Agents are **not** sources of truth; human actions are authoritative.
 
### Pause/resume pattern (canonical)
 
- **Analyze**: agent generates views (checks, OCR, classification, extracted fields)
- **Await human**: agent persists state referencing those views (not ephemeral memory)
- **Human decision**: clerk/judge/attorney reviews the frozen views and acts
- **Resume**: agent continues exactly once, using the same referenced views
 
### Exactly-once clarification
 
Exactly-once applies to:
 
- decisions
- state transitions
- intent creation
 
Not to:
 
- external delivery (emails/webhooks)
 
Pattern:
 
- exactly-once intent
- at-least-once delivery
- idempotency keys everywhere
 
## WASM vs containers
 
### WASM strengths
 
WASM is ideal for:
 
- pure/near-pure deterministic transforms
- selectors
- lightweight extraction and prechecks
- sandboxed execution with explicit inputs
 
Pinning by module hash + no ambient authority aligns well with determinism and auditability.
 
### WASM caveat
 
WASM does not *automatically* guarantee determinism unless the host controls:
 
- time
- randomness
- floating point behavior
- host capabilities
 
Inputs must still be frozen, module versions pinned, and provenance recorded.
 
### Hybrid model (recommended)
 
- **WASM**: selectors, deterministic evaluators, lightweight transforms
- **Containers**: heavy OCR, GPU work, third-party tooling
 
Both models produce views and feed the same agent/pause/resume architecture.
 
## What is decided (locked in)
 
These are architectural decisions:
 
1. Views depend only on `ContentRef`s.
2. Views are DAG nodes over `ContentRef`s.
3. Metadata used by transforms is extracted and frozen (not implicitly referenced).
4. Selection is separated into: SelectorURN, SelectorSpec, SelectionSnapshot.
5. Views depend on SelectionSnapshot blobs, not live SQL.
6. Agents pause on views, not transient state.
7. Human actions are authoritative; agents orchestrate.
8. Staging UUIDs are allowed for workflow plumbing but MUST NOT leak into content identity.
9. Determinism beats convenience.
 
## Still flexible (explicitly)
 
The following are intentionally left open to evolve:
 
- physical storage layouts / representation choices (chunking, replication, tiers)
- selector implementation language (SQL/WASM/Scala), as long as snapshots are frozen
- concrete agent runtime (Golem or alternative), as long as pause/resume semantics hold
- transform execution environment per transform class (WASM vs container)
 
## What to do next (implementation checklist)
 
1. Formalize `ContentRef` schema and invariants (BlobRef/ViewRef; validation rules).
2. Define ViewKey construction rules in code:
   - transform URN
   - params hash (canonicalized)
   - ordered input `ContentRef`s (including SelectionSnapshot `ContentRef(blob)`)
3. Implement SelectionSnapshot canonical JSON (RFC 8785 JCS) and hashing.
4. Mark transforms as order-sensitive vs order-insensitive; enforce ordering rules.
5. Define agent state machine patterns: analyze → await human → resume; exactly-once transitions.
6. Specify a minimal WASM host API (no ambient time/randomness; explicit content IO).
7. Identify first Quasar workflows to migrate to agents + views + frozen snapshots.
 
> Non-negotiable: **Do not re-run queries to reconstruct history. Always freeze inputs.**

