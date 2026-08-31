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

The `graviton` schema owns exactly 25 server tables for tenant policy and quota, catalog references, manifests and blocks, upload staging, replica repair, maintenance snapshots, Shardcake placement, security audit, and the generic object, range, and key-value runtime ports. Shared scalar domains live in `core`.

`modules/backend/graviton-pg/src/main/resources/db/migration/V001__graviton.sql` is the clean-store, PostgreSQL 16+ baseline. It enables only `pgcrypto` and `btree_gist`. It contains no document, transform, view, extraction, vector, or search schema. This pre-1.0 line deliberately has no migration or backfill path from an older combined schema.

Apply schema changes with the versioned runner:

```bash
PGPASSWORD='...' \
GRAVITON_DATABASE_URL='postgresql://admin@postgres/graviton' \
  ./scripts/migrate-postgres.sh
```

The runner serializes migrators with a PostgreSQL advisory transaction lock, records the SHA-256 of each immutable migration, skips an already-applied matching version, and fails on checksum drift. New schema changes must be added as the next `VNNN__name.sql`; never edit a migration that has been applied. CI applies the set twice to a clean PostgreSQL service and statically rejects version gaps, unexpected extensions, stale aggregate DDL, and tables outside Graviton's ownership boundary.

See [Storage Backends](../runtime/backends.md) for runtime composition and [Configuration Reference](../guide/configuration-reference.md) for connection settings.
