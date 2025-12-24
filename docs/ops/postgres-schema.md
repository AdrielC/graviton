## Postgres schema (Graviton + Quasar “alpha overhaul”)

This page documents the **authoritative Postgres schema** currently shipped in this repo, why it looks the way it does, and how to use it operationally.

If you’re looking for the DDL itself:

- **Deployable DDL**: `modules/backend/graviton-pg/src/main/resources/ddl.sql`
- **Canonical copy**: `modules/pg/ddl.sql`

### Goals

- **Correctness first**: CAS invariants are enforced by constraints, not “best effort” app code.
- **Operational hot paths**: the schema ships the SQL you actually run under load (read-plan resolution).
- **Multi-tenant safety**: Quasar tables are **partitioned by `org_id`** and protected by **Row Level Security (RLS)**.
- **Modern Postgres features (PG16+)**: generated columns, ranges, jsonb_path_ops indexes, exclusion constraints, partitioning.

---

## Overview: three logical schemas

### `core`

Shared “foundation” types and helpers:

- **Enums**: `core.hash_alg`, `core.lifecycle_status`, `core.present_status`, `core.job_status`
- **Domains**: `core.byte_size` (>= 0), `core.nonempty_text` (trimmed non-empty)
- **Helpers**:
  - `core.now_utc()` for timestamps
  - `core.touch_updated_at()` for `updated_at` maintenance
  - `core.notify_change()` for `pg_notify` invalidation payloads

### `graviton` (CAS substrate)

The content-addressable layer:

- **Topology**: `graviton.blob_store`, `graviton.sector`
- **Blocks + presence**: `graviton.block`, `graviton.block_location`, `graviton.block_verify_event`
- **Blobs + manifests**: `graviton.blob`, `graviton.blob_manifest_page`, `graviton.blob_block`
- **Transforms + views**: `graviton.transform`, `graviton.view`, `graviton.view_input`, `graviton.view_op`, `graviton.view_materialization`
- **Hot path helpers**:
  - `graviton.best_block_locations(...)`
  - `graviton.v_best_block_location`
  - `graviton.manifest_pages(...)`
  - `graviton.resolve_blob_read_plan(...)`

### `quasar` (application substrate)

The “documents + permissions + jobs” layer:

- **Tenancy**: `quasar.tenant`, `quasar.org`
- **Principals**: `quasar.principal`, `quasar.principal_external_identity`
- **Uploads**: `quasar.upload_session`, `quasar.upload_file`, `quasar.upload_part`
- **Documents**: `quasar.document`, `quasar.document_version`, `quasar.document_current`, `quasar.document_alias`
- **Metadata**: `quasar.namespace`, `quasar.schema_registry`, `quasar.document_namespace`
- **Permissions**: `quasar.acl_entry`, `quasar.policy`
- **Jobs**: `quasar.outbox_job`
- **Convenience view** (PG16-safe JSON extraction): `quasar.v_doc_upload_claims`

---

## Graviton: invariants and “don’t let me screw it up”

### CAS keys

We store CAS identity as \((alg, hash\_bytes, byte\_length)\) and validate it with:

- `graviton.is_valid_cas_key(...)`
- `CHECK` constraints on `graviton.block` and `graviton.blob`

### Locator JSON contract (block presence)

Physical placement is encoded in `graviton.block_location.locator` (JSONB), and we enforce minimum structure:

- `locator` is an object
- `scheme` must exist and match a real URI scheme regex
- scheme-specific required keys:
  - `s3`: requires `bucket` + `key`
  - `fs`: requires `path`
  - `ceph`: requires `pool` + `key`

We also persist a generated `locator_canonical` string for debugging and quick equality checks.

### Non-overlapping manifests

`graviton.blob_block` includes a generated `span` range and a GiST exclusion constraint:

- **No overlapping block spans** for the same blob.
- This is a huge correctness win: you can’t write a logically corrupt manifest into the DB.

---

## Quasar: partitioning + RLS (full “hard to misuse” mode)

### Partitioning

Most Quasar tables are **hash partitioned by `org_id`** (16 partitions) to keep:

- indexes smaller and hotter
- vacuum/autovac and bloat more manageable
- large tenants from dominating the working set

This is why you see composite primary keys like:

- `PRIMARY KEY (org_id, doc_id)`
- `PRIMARY KEY (org_id, doc_version_id)`

And org-aware foreign keys like:

- `FOREIGN KEY (org_id, principal_id) REFERENCES quasar.principal(org_id, principal_id)`

### Row Level Security (RLS)

The app should set the active org per transaction:

```sql
SET LOCAL app.org_id = '00000000-0000-0000-0000-000000000000';
```

Then Postgres enforces isolation with policies like:

- `USING (org_id = quasar.current_org_id())`

Special case: `quasar.schema_registry` allows both:

- org-scoped schemas: `org_id = current_org_id()`
- global schemas: `org_id IS NULL`

---

## Hot path: “resolve content to bytes”

This schema ships the read-path primitives so your application can stay thin:

### 1) Pick best physical candidates for a block

Use `graviton.best_block_locations(...)`:

```sql
select *
from graviton.best_block_locations('sha256', $1::bytea, $2::bigint, 5);
```

Ordering is:

1. `sector.priority` ASC (lower is better)
2. `block_location.verified_at` DESC NULLS LAST
3. `block_location.written_at` DESC

### 2) Stream manifest pages for a blob

Use `graviton.manifest_pages(...)`:

```sql
select page_no, entry_count, entries
from graviton.manifest_pages('sha256', $1::bytea, $2::bigint);
```

### 3) One-shot plan (blob → ordered spans → best location)

Use `graviton.resolve_blob_read_plan(...)`:

```sql
select *
from graviton.resolve_blob_read_plan('sha256', $1::bytea, $2::bigint)
order by ordinal;
```

This is meant for:

- repair tooling
- diagnostics (“why can’t we read this blob?”)
- building a streaming plan in the app layer

---

## Change notifications (pg_notify)

The DB emits invalidation events for key tables via `core.notify_change()`:

- **channel `graviton_inval`**: blob_store/sector/block_location/manifest tables
- **channel `quasar_inval`**: document_current + outbox_job

Payload is JSON including schema, table, op, ts, and the affected row.

---

## Scala dbcodegen (generated bindings)

The dbcodegen tool is now expected to generate **one schema file per schema**:

- `modules/pg/src/main/scala/graviton/pg/generated/graviton/schema.scala`
- `modules/pg/src/main/scala/graviton/pg/generated/quasar/schema.scala`

Run it (local Postgres):

```bash
PG_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/<db> \
PG_USERNAME=postgres \
PG_PASSWORD=postgres \
./sbt "set Compile / run / javaOptions += \"-Ddbcodegen.out=modules/pg/src/main/scala/graviton/pg/generated\"" \
run
```

---

## Notes / future extensions

- **PG18 UUIDv7**: once we target PG18, we can swap primary key defaults to `uuidv7()` for even nicer index locality.
- **Manifest partitioning**: if blob manifests get enormous, we can additionally partition by hash prefix in Graviton.

