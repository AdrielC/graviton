# PostgreSQL Storage

PostgreSQL is optional. The default filesystem runtime stores blocks and manifests on disk and does not require a database.

The distributed S3-compatible composition uses PostgreSQL for Graviton metadata while block bytes remain in object storage:

- `PgBlobManifestRepo` commits blob identity and ordered block spans transactionally;
- `PgCatalog` stores mutable folder and file references to immutable CAS blobs;
- `PgReplicaIndex` records block placement;
- `PgMaintenanceCoordinator` provides the shared/exclusive advisory-lock protocol used by ordinary traffic and maintenance;
- the object, key-value, and range adapters provide reusable PostgreSQL-backed runtime ports.

The application server does not expose a document model through these tables. Catalog folders are operator-facing organization for CAS references, not document hierarchy, versions, permissions, search, or workflow state.

## Current schema boundary

The `graviton` schema owns the server's catalog, manifest, replica, and maintenance data. Shared scalar domains live in `core`.

The repository currently retains a combined bootstrap DDL resource that also contains tables for an unpublished, source-only document-layer prototype. The packaged Graviton server does not depend on or expose that prototype, but the resource has not yet been physically split. Treat extracting a Graviton-only migration set as remaining repository cleanup, not as a shipped document feature.

See [Storage Backends](../runtime/backends.md) for runtime composition and [Configuration Reference](../guide/configuration-reference.md) for connection settings.
