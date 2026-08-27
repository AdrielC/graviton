# ADR 0004: Backend-wide Maintenance Coordination

## Status

Accepted.

## Context

Content-addressed blocks and blob manifests are persisted through independent stores. An upload can therefore persist a block before it commits the manifest that makes the block reachable. A collector that inventories both stores without coordinating writers could quarantine that block in the gap.

Minimum age and a second mark narrow the race, but they do not create an atomic cross-store maintenance boundary. A process-local mutex also cannot coordinate independent server and CLI processes.

## Decision

Graviton defines a storage-orthogonal `MaintenanceCoordinator` ZIO service with two scoped resources:

- ordinary blob operations acquire a shared `operationPermit`
- destructive maintenance acquires an exclusive `maintenanceLease`

`CoordinatedBlobStore` holds the shared permit across the complete logical resource lifetime. For uploads, that includes consumption of the returned `ZSink`. For downloads, it includes demand on the returned `ZStream`. Metadata, inventory, inspection, verification, and deletion are also coordinated.

`GarbageCollection.live` requires a coordinator explicitly and holds the exclusive lease across preview, mark, second mark, quarantine, restore, or purge work. Acquisition has a typed timeout, is interruptible, and registers cleanup in `Scope` immediately after success.

The built-in implementations are:

- `LocalMaintenanceGate`, a writer-preferring STM reader/writer gate for fibers in one runtime
- `FileMaintenanceCoordinator`, which combines the local gate with a shared or exclusive file lock at `<root>/cas/.maintenance.lock`
- `PgMaintenanceCoordinator`, which combines the local gate with namespaced PostgreSQL shared or exclusive session advisory locks

All processes that reach one manifest and block repository must select the same filesystem root or PostgreSQL maintenance namespace and must use coordinated blob-store construction. `Graviton.fs`, `Graviton.inMemory`, the packaged server, and the CLI do so by default.

## Consequences

- garbage collection cannot observe the gap between block persistence and manifest commit in built-in compositions
- maintenance waits for active streams, not merely for sink or stream construction
- a waiting maintenance lease prevents new local operations from starving it
- interruption and timeout release local and backend resources
- filesystem coordination depends on working cross-client file-lock semantics
- PostgreSQL coordination consumes a session while a process holds its shared lock and a separate session during exclusive maintenance
- sustained operations from independent processes can make an exclusive acquisition time out; operators should drain traffic or retry rather than bypass coordination
- raw `CasBlobStore` construction and old `GarbageCollector` constructors remain uncoordinated compatibility escape hatches
- minimum age, exact marking, the second mark, quarantine, and compensating restore remain defense in depth
- backup consistency remains separate because the current backup script does not acquire the maintenance lease

## Proof

The focused suites prove concurrent shared operations, exclusive waiting, writer preference, acquisition timeout, interruption cleanup, complete download-stream lifetime, independent filesystem coordinator instances, an external file-lock holder, independent PostgreSQL sessions, and the upload-versus-GC race at the block-persisted/manifest-uncommitted boundary.
