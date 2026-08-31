# Multi-Tenant Storage

The packaged HTTP and gRPC server can bind an authenticated organization to a durable, server-owned tenant policy. Enable it with:

```bash
export GRAVITON_MULTI_TENANT_ENABLED=true
export GRAVITON_MULTI_TENANT_CELL_ID=default
```

Startup fails unless security, TLS enforcement, and durable JDBC audit are enabled. Development shared-secret authentication is rejected. The server accepts the organization UUID only from the verified token, resolves that UUID in PostgreSQL, and establishes `TenantContext` before a storage effect is constructed. A missing, suspended, wrong-cell, or unknown tenant fails closed without pulling upload bytes.

## Isolation modes

| Mode | Blocks | Manifests | Credentials and blast radius | Intended use |
| --- | --- | --- | --- | --- |
| Pooled, isolated | Private hashed storage prefix per tenant | Private rows per tenant | Shared service credentials and database | Default for unrelated customers inside one deployment cell |
| Shared trust group | Shared hashed prefix only inside one named domain | Still private per tenant | Shared service credentials and block-membership signal inside the group | Organizations that explicitly accept cross-tenant deduplication |
| Silo deployment cell | Separate Graviton deployment, PostgreSQL, buckets, credentials, and encryption policy | Separate database | Independent infrastructure boundary | Residency, regulated data, customer-managed keys, or stricter contractual isolation |

`DeduplicationScope.Isolated` is the policy default. A shared domain requires both a non-null `deduplication_domain` in the tenant policy and a server-wide `GRAVITON_TENANT_STORAGE_ALLOW_SHARED_DEDUPLICATION=true` opt-in. Either missing condition rejects the route. Callers cannot provide or override the domain.

Deduplication is not access sharing. Two tenants in one shared domain can reuse identical physical blocks, but each has a separate manifest namespace. One tenant cannot list, read, or delete another tenant's blob merely because both reference the same blocks. Tenant manifests and retained-usage rows also use forced PostgreSQL row-level security under a `NOBYPASSRLS` runtime role, so an omitted SQL predicate fails closed independently of the typed repository boundary.

Fresh-versus-duplicate results are sensitive in a shared domain because they expose a content-membership signal. Treat every shared domain as a named trust boundary, not a cost toggle.

## Durable tenant policy

`graviton.tenant_storage_policy` owns:

- deployment `cell_id`;
- active or suspended lifecycle;
- optional shared deduplication domain;
- concurrent-operation ceiling;
- streamed single-object byte ceiling;
- cluster-wide retained logical byte ceiling;
- monotonic revision.

The database trigger increments the revision on every policy update. Each node caches policy for a bounded TTL and replaces its immutable store binding when the revision or storage domain changes. Tightening a concurrency policy while requests are active fails new admission until the old permit set drains.

Provision or update a policy without interpolating SQL by hand:

```bash
export PG_ADMIN_USERNAME=postgres
export PG_ADMIN_PASSWORD='control-plane-secret'
./scripts/provision-tenant.sh \
  4d0af98d-e784-4af9-a1fe-6a8922f76472 \
  isolated \
  default \
  5368709120 \
  1099511627776 \
  32
```

The packaged production topology gives `graviton_app` only data-plane access and read-only policy access. Provision with a separate control-plane credential. Do not expose that credential to Graviton nodes.

For a trust group, replace `isolated` with `shared:research-consortium` and explicitly enable shared deduplication on every server in that cell.

## Quotas and noisy-neighbor controls

The stock server enforces six bounded controls:

1. process-wide `TransferBudget` limits aggregate live byte buffers;
2. per-tenant admission limits concurrent logical operations;
3. per-principal request, upload-byte, and download-byte token buckets bound one process;
4. PostgreSQL serializes retained-byte accounting with manifest publication and deletion.
5. Optional Redis or Valkey leases atomically cap active bytes and transfers across the complete service, each tenant, and each backend.
6. Optional Redis or Valkey counters enforce authenticated HTTP request and delivered-egress ceilings per tenant using Redis server time.

The retained-byte row is locked in the same transaction that publishes a manifest. Concurrent writers on different nodes cannot collectively exceed the tenant limit. Re-uploading the exact same tenant blob is idempotent and does not consume the quota twice. Deleting a tenant manifest releases its logical bytes. HTTP reports `507 tenant_storage_quota_exceeded`; gRPC reports `RESOURCE_EXHAUSTED`.

Per-principal token buckets remain process-local load shedding. When Redis or Valkey admission is enabled, authenticated HTTP requests also charge a cluster-atomic tenant request counter, and response chunks charge a cluster-atomic tenant delivered-egress counter as they leave the server. The current gRPC interceptor uses the process-local request, upload-byte, and download-byte limiter and does not charge those distributed traffic counters. Put contractual cross-protocol traffic accounting at the authenticated edge until gRPC parity is implemented. The retained storage quota is separately cluster-atomic.

The optional coordinator prevents a busy organization from consuming the entire shared transfer pool by applying its tenant byte and concurrency ceilings in the same atomic state transition as service and backend ceilings. `DistributedAdmissionControl` can tighten or relax one tenant without restarting data nodes. Every admission, release, expiry, queue, timeout, and policy change appends a bounded decision event with occupancy and policy version, so an external scheduled or predictive controller can propose and apply reviewed overrides. Tenant identifiers are SHA-256 keyed in Redis and never used as metric labels.

Tenant policy, store, admission, and principal-rate registries are bounded and split across deterministic shards. Resolution occurs once per upload, download, range, inventory, inspect, delete, stat, or health operation, never once per byte or block. Tenant IDs are excluded from metric labels.

## ZIO context and Shardcake locality

`TenantContext` is a scoped service backed by a parent-preserving `FiberRef[Option[TenantId]]`. Child fibers inherit the tenant, sibling fibers remain isolated, child completion cannot overwrite the parent, and interruption restores the prior region.

Shardcake is orthogonal. Its `(tenant, upload session)` key keeps an upload on one owner for locality, but Shardcake does not authenticate callers or select storage policy. The owner resolves the tenant again before consuming the one-shot stream. Shardcake control envelopes use ZIO Blocks MessagePack, not Kryo.

## Cell-wide coordination and shared-domain maintenance

The packaged multi-tenant server derives one PostgreSQL advisory-lock namespace per deployment cell. All in-process tenant operations share one coordinator, so hundreds of concurrent operations coalesce onto one leased database connection instead of consuming one connection per tenant. Exclusive maintenance intentionally drains the complete cell. Use smaller cells when that maintenance blast radius is unacceptable.

A shared block domain must be maintained as one unit. Garbage collection against only one tenant's manifests can mistake another tenant's live blocks for orphans.

The packaged replicated data plane now captures every cell membership in a repeatable-read PostgreSQL transaction. The immutable snapshot includes isolated and shared domains, policy revisions, a deterministic membership digest, and bounded streamed member cursors. A scoped worker uses that snapshot to scrub one domain at a time and persists a convergence cursor and dead letters without loading the tenant population in memory. It retains the latest 32 snapshot records.

Before quarantining or purging a shared domain:

1. acquire the cell-wide maintenance lease;
2. freeze the domain membership and catalog revision;
3. stream every tenant manifest repository in that snapshot;
4. mark, re-mark, and quarantine;
5. retain receipts and complete the restore window;
6. purge and release the lease.

`GarbageCollector.forStorageDomain`, `ManifestReferenceSource`, and `PgTenantDomainSnapshot` implement the bounded streaming mark and membership sides. Automated replica convergence is safe to run unattended. Quarantine and purge remain explicit operator changes with retained receipts.

## Rollout sequence

1. Apply `modules/backend/graviton-pg/src/main/resources/ddl.sql`.
2. Create one deployment cell with distinct database and object-store credentials.
3. Provision isolated policies for a small canary group.
4. Enable the packaged multi-tenant data plane on one canary node.
5. Prove HTTP and gRPC cross-tenant denial, retained quota races, restart, rollback, and target failover.
6. Load-test the exact IdP, ingress, PostgreSQL pool, object store, payload distribution, and node count.
7. Expand the cell gradually. Create separate cells for customers whose isolation contract requires independent credentials or infrastructure.
8. Enable a shared domain only after the participating organizations and operator accept its disclosure and encryption boundary.

## Current deployment boundary

Implemented and exercised in the repository: authenticated HTTP and gRPC tenant binding, durable PostgreSQL policy, cell filtering, private manifests, isolated or explicit shared block domains, cluster-atomic retained quotas, bounded sharded local admission, optional cluster-wide Redis or Valkey transfer admission, Shardcake tenant routing, full-quorum replicated writes, and typed protocol failures.

Not established by repository tests alone: a universal customer count, billing, customer-managed key integration, physical database or object-store service levels, real IdP and ingress acceptance, or a production Ceph capacity envelope. Multi-tenant erasure coding is rejected at startup until domain-wide erasure repair inventory exists. Multi-tenant replication requires every configured target in both placement and write quorum; reads repair damage on demand, and the scheduled snapshot-backed domain scrub converges cold blocks. Atomic HTTP request and delivered-egress contracts require the Redis or Valkey provider and must be sized and failover-qualified in the target cell. gRPC traffic remains process-limited rather than cluster-metered.
