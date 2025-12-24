## Postgres schema (Graviton + Quasar “alpha overhaul”)

This page documents the **authoritative Postgres schema** currently shipped in this repo, why it looks the way it does, and how to use it operationally.

If you’re looking for the DDL itself:

- **Deployable DDL**: `modules/backend/graviton-pg/src/main/resources/ddl.sql`
- **Canonical copy**: `modules/pg/ddl.sql`

If you’re looking for **code you can copy/paste into psql right now**, keep reading — this page is intentionally heavy on SQL.

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

Here is the actual “presence row” shape (simplified but accurate; see the DDL for the full table):

```sql
-- physical materialization (where bytes actually live)
create table graviton.block_location (
  block_location_id uuid primary key default gen_random_uuid(),

  -- logical identity
  alg         core.hash_alg not null,
  hash_bytes  bytea not null,
  byte_length core.byte_size not null,

  -- placement
  sector_id uuid not null references graviton.sector(sector_id),

  -- backend-native locator contract
  locator jsonb not null,

  -- debuggable canonical string
  locator_canonical text generated always as (
    coalesce(locator->>'scheme','') || '://' ||
    coalesce(locator->>'host', locator->>'bucket', '') || '/' ||
    coalesce(locator->>'key', locator->>'path', '')
  ) stored,

  stored_length core.byte_size not null,
  frame_format int not null default 1,
  encryption jsonb not null default '{}'::jsonb,
  status core.present_status not null default 'present',

  written_at timestamptz not null default core.now_utc(),
  verified_at timestamptz null,

  foreign key (alg, hash_bytes, byte_length)
    references graviton.block(alg, hash_bytes, byte_length),

  constraint locator_is_object check (jsonb_typeof(locator) = 'object'),
  constraint locator_has_scheme check (locator ? 'scheme'),
  constraint locator_has_keyish check ((locator ? 'key') or (locator ? 'path')),

  -- sicko contract enforcement
  constraint locator_scheme_format check ((locator->>'scheme') ~ '^[a-z][a-z0-9+.-]*$'),
  constraint locator_scheme_contract check (
    case locator->>'scheme'
      when 's3' then (locator ? 'bucket') and (locator ? 'key')
      when 'fs' then (locator ? 'path')
      when 'ceph' then (locator ? 'pool') and (locator ? 'key')
      else true
    end
  )
);
```

Example locators (these are the minimum shapes that pass checks):

```sql
-- s3
select jsonb_build_object(
  'scheme','s3',
  'bucket','graviton-prod',
  'key','blocks/sha256/ab/cd/abcd...',
  'region','us-east-1'
);

-- fs
select jsonb_build_object(
  'scheme','fs',
  'path','/var/lib/graviton/blocks/sha256/ab/cd/abcd...'
);

-- ceph
select jsonb_build_object(
  'scheme','ceph',
  'pool','graviton',
  'key','blocks/sha256/ab/cd/abcd...'
);
```

### Non-overlapping manifests

`graviton.blob_block` includes a generated `span` range and a GiST exclusion constraint:

- **No overlapping block spans** for the same blob.
- This is a huge correctness win: you can’t write a logically corrupt manifest into the DB.

This is the “no overlapping spans” trick in SQL (actual constraint form):

```sql
-- blob_block.span is generated:
--   [block_offset, block_offset + block_length)
alter table graviton.blob_block
  add column span int8range generated always as (
    int8range(block_offset, block_offset + block_length, '[)')
  ) stored;

-- forbid overlaps for the same blob key
alter table graviton.blob_block
  add constraint blob_block_non_overlapping
  exclude using gist (
    alg with =,
    hash_bytes with =,
    byte_length with =,
    span with &&
  );
```

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

Here’s the “shape” of the partitioned document read-model (this is the hot path you list/search over):

```sql
create table quasar.document_current (
  org_id uuid not null,
  doc_id uuid not null,
  current_version_id uuid not null,

  content_kind quasar.content_kind not null,
  content_ref jsonb not null,

  status quasar.doc_status not null,
  title text not null,
  last_modified_at timestamptz not null default core.now_utc(),

  primary key (org_id, doc_id),

  -- org-aware FKs (the whole point)
  foreign key (org_id, doc_id)
    references quasar.document(org_id, doc_id) on delete cascade,

  foreign key (org_id, current_version_id)
    references quasar.document_version(org_id, doc_version_id),

  constraint content_ref_is_object check (jsonb_typeof(content_ref) = 'object')
) partition by hash (org_id);

-- 16 hash partitions (p0..p15)
do $$
begin
  for i in 0..15 loop
    execute format(
      'create table if not exists quasar.document_current_p%1$s partition of quasar.document_current for values with (modulus 16, remainder %1$s)',
      i
    );
  end loop;
end $$;
```

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

This is what the policy actually looks like:

```sql
create or replace function quasar.current_org_id()
returns uuid language sql stable as $$
  select nullif(current_setting('app.org_id', true), '')::uuid;
$$;

alter table quasar.document_current enable row level security;
create policy quasar_document_current_org_isolation
  on quasar.document_current
  using (org_id = quasar.current_org_id());
```

---

## Hot path: “resolve content to bytes”

This schema ships the read-path primitives so your application can stay thin:

### 1) Pick best physical candidates for a block

Use `graviton.best_block_locations(...)`:

```sql
select *
from graviton.best_block_locations('sha256', $1::bytea, $2::bigint, 5);
```

Here’s the full function definition (copy/pasteable):

```sql
create or replace function graviton.best_block_locations(
  p_alg core.hash_alg,
  p_hash_bytes bytea,
  p_byte_length bigint,
  p_limit int default 5
)
returns table (
  sector_priority int,
  sector_id uuid,
  blob_store_id uuid,
  blob_store_type_id text,
  block_location_id uuid,
  status core.present_status,
  locator jsonb,
  locator_canonical text,
  stored_length bigint,
  frame_format int,
  encryption jsonb,
  written_at timestamptz,
  verified_at timestamptz
)
language sql stable as $$
  select
    s.priority as sector_priority,
    s.sector_id,
    s.blob_store_id,
    bs.type_id as blob_store_type_id,
    bl.block_location_id,
    bl.status,
    bl.locator,
    bl.locator_canonical,
    bl.stored_length,
    bl.frame_format,
    bl.encryption,
    bl.written_at,
    bl.verified_at
  from graviton.block_location bl
  join graviton.sector s
    on s.sector_id = bl.sector_id
  join graviton.blob_store bs
    on bs.blob_store_id = s.blob_store_id
  where bl.alg = p_alg
    and bl.hash_bytes = p_hash_bytes
    and bl.byte_length = p_byte_length
    and bl.status = 'present'
    and s.status = 'active'
    and bs.status = 'active'
  order by
    s.priority asc,
    bl.verified_at desc nulls last,
    bl.written_at desc
  limit greatest(p_limit, 0);
$$;
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

### Scala usage: call the hot path

The dbcodegen output is now per-schema:

- `graviton.pg.generated.graviton.*`
- `graviton.pg.generated.quasar.*`

Here’s a minimal “call the function” snippet using Magnum:

```scala
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import graviton.db.{*, given}
import graviton.pg.generated.graviton as g
import zio.*

def bestCandidates(
  xa: TransactorZIO,
  alg: g.HashAlg,
  hash: Chunk[Byte],
  bytes: Long,
): Task[Chunk[(Int, java.util.UUID, Json)]] =
  xa.transact {
    sql"""
      select sector_priority, sector_id, locator
      from graviton.best_block_locations($alg, $hash, $bytes, 5)
    """.query[(Int, java.util.UUID, Json)].run().map(Chunk.fromIterable)
  }
```

---

## Change notifications (pg_notify)

The DB emits invalidation events for key tables via `core.notify_change()`:

- **channel `graviton_inval`**: blob_store/sector/block_location/manifest tables
- **channel `quasar_inval`**: document_current + outbox_job

Payload is JSON including schema, table, op, ts, and the affected row.

This is the notification trigger body (actual shape):

```sql
create or replace function core.notify_change()
returns trigger language plpgsql as $$
declare
  payload jsonb;
  chan text;
begin
  chan := case tg_table_schema
    when 'graviton' then 'graviton_inval'
    when 'quasar' then 'quasar_inval'
    else 'core_inval'
  end;

  payload := jsonb_build_object(
    'schema', tg_table_schema,
    'table',  tg_table_name,
    'op',     tg_op,
    'ts',     clock_timestamp(),
    'row',    case when tg_op in ('insert','update') then to_jsonb(new) else to_jsonb(old) end
  );

  perform pg_notify(chan, payload::text);
  return coalesce(new, old);
end;
$$;
```

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

