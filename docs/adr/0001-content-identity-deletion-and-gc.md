# ADR 0001: Content Identity, Deletion, and Garbage Collection

## Status

Accepted for 0.1.

## Decision

Blob and block identity is derived only from hash algorithm, digest, and byte length. Tenant, location, timestamps, permissions, and transport metadata never change a content key.

Deleting a blob removes its manifest. It does not synchronously delete blocks because blocks may be shared by other manifests. Unreachable blocks are reclaimed by a separate collector that:

1. marks all referenced block keys
2. selects only unreferenced blocks older than a configured minimum age
3. marks again immediately before mutation
4. moves candidates into quarantine
5. permits restore during a retention window
6. purges only through an explicit later operation

## Consequences

- retries naturally converge on the same content identity
- deduplication remains safe across logical blobs
- logical deletion is fast but physical capacity is recovered later
- operators must treat quarantine retention and purge as separate change controls
- legal erasure requirements need an explicit retention and encryption design beyond manifest deletion
