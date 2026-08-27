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

The implementation streams backend inventories and manifest references into a temporary, exact disk-spilled join. It holds only one bounded digest partition in heap and rechecks the persisted candidate set against a fresh mark before quarantine. A receipt sink is invoked for each move; a receipt failure triggers a compensating restore for that block.

Production collection holds a backend-wide exclusive maintenance lease for the complete sweep. Blob operations hold shared permits for the complete upload, download, inspection, listing, verification, or deletion resource lifetime. Filesystem repositories coordinate with shared and exclusive file locks. S3 plus PostgreSQL repositories coordinate with namespaced PostgreSQL session advisory locks. All processes that reach one repository must use the same coordinator and namespace.

## Consequences

- retries naturally converge on the same content identity
- deduplication remains safe across logical blobs
- logical deletion is fast but physical capacity is recovered later
- operators must treat quarantine retention and purge as separate change controls
- temporary workspace capacity is an explicit maintenance prerequisite
- the maintenance lease closes the upload-versus-collection race across independent manifest and block stores
- minimum age and the second mark remain defense in depth for abandoned blocks and compatibility callers
- low-level uncoordinated constructors remain a compatibility escape hatch and are not a production GC composition
- backup consistency still requires a maintenance window or a storage-level snapshot protocol
- legal erasure requirements need an explicit retention and encryption design beyond manifest deletion
