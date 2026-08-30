# Multi-Tenant Storage

Graviton now provides an embeddable multi-tenant data-plane boundary. It is not enabled by the packaged server. Applications that mount it must authenticate the caller, bind the authenticated organization to `TenantId`, and enter `TenantContext.locally` before constructing or running a storage effect.

## Isolation modes

Every `TenantRoute` selects one of two physical layouts:

| Mode | Blocks | Manifests | Intended use |
| --- | --- | --- | --- |
| `DeduplicationScope.Isolated` | Private storage domain per tenant | Private repository per tenant | Default for unrelated customers, regulated data, separate residency, or separate encryption keys |
| `DeduplicationScope.Shared(domainId)` | Shared only with tenants in the same named domain | Still private per tenant | Explicit trust groups that accept cross-tenant block-membership disclosure in exchange for space savings |

Isolation is the default twice. `TenantRoute` defaults to `Isolated`, and `TenantStorageConfig` defaults `allowSharedDeduplication` to `false`. A topology containing a shared route fails construction until the embedding process explicitly enables:

```hocon
graviton.tenant-storage.allow-shared-deduplication = true
```

That setting does not place tenants into a group. Each shared route must still name its refined `DeduplicationDomainId`.

## Request boundary

`TenantContext` is a scoped ZIO service backed by `FiberRef[Option[TenantId]]`:

- child fibers inherit the active tenant;
- a child cannot overwrite its parent's tenant when it completes;
- sibling fibers remain isolated;
- interruption restores the previous context;
- missing and unknown tenants fail with typed `StoreError` values before an upload source is pulled.

`ContextualTenantBlobStore` resolves `TenantStoreProvider` once at the start of each logical upload, download, range, inventory, inspect, delete, stat, or health operation. Resolution is outside the per-byte and per-block hot path. `TenantStoreProvider.fromFunction` supports a database-backed or bounded-cache control plane, so large installations do not have to preload every tenant into `TenantStoreProvider.static`.

Resolution count, outcome, scope, and duration use bounded metric labels. Tenant IDs never become metric labels.

Shardcake is orthogonal. Its `(tenant, upload session)` key provides upload-owner locality. It does not authenticate a tenant, choose a storage domain, or provide data isolation. The owner must re-establish validated tenant context before entering the tenant-aware `BlobStore`.

## Shared-domain garbage collection

A shared block domain must be maintained as one unit. Running garbage collection against one tenant's manifests can mistake another tenant's live blocks for orphans.

Use `GarbageCollector.forStorageDomain` with a `NonEmptyChunk` containing every manifest repository that can reference the block domain, or provide `ManifestReferenceSource` to `GarbageCollection.storageDomainLive`. `ManifestReferenceSource.repositories` walks repositories, manifest pages, and block references sequentially. It does not collect repository-scale keys or keep one cursor open per tenant. The maintenance coordinator supplied to the collector must be the same backend-wide lease used by every writer in that domain.

Tenant onboarding, removal, garbage collection, and snapshots therefore need a control-plane protocol:

1. acquire the domain-wide maintenance lease;
2. freeze the domain membership/catalog version;
3. enumerate every manifest repository in that snapshot;
4. mark, re-mark, and quarantine through the streaming collector;
5. retain receipts before purge;
6. release the lease.

The repository proves the in-process mark behavior and coordinator contract. A PostgreSQL, S3, Ceph, or Kubernetes deployment must qualify its catalog snapshot, lease, and backup behavior in the target environment.

## Scaling and safety checklist

- Use a process-wide `TransferBudget` shared across all tenant stores. Per-tenant budgets alone can multiply past heap limits.
- Keep tenant, session, blob, and node identifiers out of metric labels. Use bounded operation and outcome labels, then put identities only in access-controlled traces or audit records.
- Apply request, byte, concurrency, retained-storage, and egress quotas before entering storage. The tenant router is an isolation boundary, not a billing system.
- Use distinct buckets, prefixes, database schemas, credentials, encryption keys, and maintenance namespaces when the isolation policy requires them. The in-process topology detects reused object instances, but it cannot prove that two separately constructed adapters do not point at the same external namespace.
- Never let a caller choose `TenantId` or `DeduplicationDomainId` directly. Resolve both from authenticated server-side policy.
- Normalize missing, unknown, and unauthorized tenant failures at the public protocol boundary so callers cannot enumerate tenant IDs. Preserve the typed internal cause only in access-controlled audit records.
- Treat fresh-versus-duplicate statistics as sensitive in a shared domain. They can reveal whether selected content already exists in that trust group.
- Scope encryption and key rotation to the storage domain. Per-tenant ciphertext prevents cross-tenant physical reuse; a shared domain key increases the trust and incident blast radius and must never be implied by merely naming a deduplication domain.
- Qualify noisy-neighbor behavior with representative concurrency and backend limits. The structural test proves one tenant lookup per logical 8 MiB stream operation, not a customer-capacity number.

## Current deployment boundary

The runtime types, fail-closed router, explicit sharing policy, domain-wide GC source, and published tenant laws are implemented. The stock HTTP and gRPC server still mounts one storage composition and is not advertised as a turnkey multi-tenant object service. Production rollout still needs application-specific identity binding, tenant catalog durability, quotas, residency/encryption policy, domain membership snapshots, billing, and target-scale load evidence.
