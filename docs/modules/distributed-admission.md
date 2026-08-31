# Distributed Transfer Admission

`graviton-admission-redis` is the optional Redis or Valkey adapter for cluster-wide transfer fairness. The provider composes with the process-local `TransferBudget`; it does not replace the hard memory boundary and it never transports payload bytes.

```scala
libraryDependencies += "io.github.adrielc" %% "graviton-admission-redis" % gravitonVersion
```

## Admission hierarchy

One acquisition atomically checks and reserves all applicable dimensions:

1. service buffered bytes;
2. service transfer count;
3. tenant buffered bytes;
4. tenant transfer count;
5. physical backend transfer count.

The runtime first reserves hard local process, tenant, and backend permits, then requests the cluster lease. Both layers complete before upload input, a resumable-part request body, a manifest, or a block source is demanded. The packaged server reserves a conservative 128 MiB footprint for each active resumable staging part and attributes it to the authenticated tenant and physical staging backend. This ordering cannot expose the JVM to an uncoordinated chunk even when Redis is slow or unavailable.

Per-tenant byte and transfer ceilings stop one organization from taking the complete service pool. `DistributedAdmissionControl` applies a live tenant override and returns the monotonic policy version used by later acquisitions. Clearing an override restores configured defaults.

## Traffic quotas

The same provider also implements a separate `DistributedTrafficQuota` contract. Authenticated HTTP requests charge one tenant request counter per Redis-server-time minute. HTTP download chunks charge delivered bytes per tenant and Redis-server-time hour as the chunks leave the server. These counters are atomic across provider instances and fail closed when enabled but unavailable.

This traffic contract is currently wired to HTTP only. The packaged gRPC interceptor still uses process-local request, upload-byte, and download-byte limits. Do not describe the Redis counters as cross-protocol billing or edge metering until gRPC or an external authenticated edge charges the same contract.

## Atomic lease protocol

The adapter uses one static Lua program for acquire, renew, release, expiry reaping, snapshot, policy change, and event append. Every key includes the same cell hash tag, so the complete state transition remains in one Redis Cluster slot. Redis server time, rather than node clocks, determines expiry.

An admitted transfer receives an Iron-refined lease ID, a monotonic fencing token, the policy version, and its post-admission occupancy. The lease renews in a scoped fiber and releases for success, failure, or interruption. A crashed process is recovered by bounded expiry reaping. A stale owner cannot renew or release a newer lease because the fencing token must match.

New acquisitions fail closed on coordinator errors. The local process reservation remains held while cluster admission waits and is released on timeout, failure, cancellation, or ordinary scope exit. If an active lease can no longer be renewed, Graviton emits a lease-loss metric and log. The local budget remains authoritative for bytes already resident until the transfer exits; operators must qualify coordinator failover and choose lease timing that matches the target network.

## Events and predictive control

The same atomic transition appends a bounded Redis Stream record with schema `graviton-admission-event-v1`. Records contain:

- decision kind and Redis server timestamp;
- lease ID and fencing token where applicable;
- SHA-256 tenant and backend keys;
- transfer footprint, operation, and outcome;
- policy version;
- service, tenant, and backend occupancy after the transition.

Kinds cover admitted, queued, timed out, completed, interrupted, failed, expired, and policy changed. This is enough for an external scheduled or predictive controller to correlate pressure, propose a bounded override, and apply it through `DistributedAdmissionControl`. Graviton deliberately does not embed an autonomous policy model into the byte engine.

Metric labels contain only bounded operation, backend, and outcome values. Tenant IDs are never labels. Raw tenant IDs are also absent from Redis keys and event fields.

## Serialization and secrets

Coordinator values are plain RESP strings and integers with a versioned event schema. There is no Kryo, Java serialization, or arbitrary object graph. Upload and download bytes stay on ZIO Streams between the client and storage backend.

The public `RedisAdmissionConfig` stores the password as `Config.Secret`, whose rendering is redacted. zio-redis 1.2.1 exposes a raw connection config that renders authentication material, so Graviton keeps that conversion provider-private and never adds it to logs or typed errors.

Tenant mode requires TLS, certificate verification, authentication, and a cell ID matching the tenant data plane. Configuration rejects unsafe lease intervals, inconsistent ceilings, an undersized service byte pool, and a Redis wait longer than the outer local acquisition timeout.

## Evidence

```bash
GRAVITON_REDIS_IT=1 ./sbt redisAdmission/test
./sbt runtime/test server/test
```

The integration suite connects two independent provider instances to one real Redis-compatible server and proves cross-node atomicity, tenant independence, request and delivered-egress counters, live policy changes, renewal past the original TTL, interruption release, server-time expiry reaping, immediate fencing-loss propagation, and bounded event publication. Runtime tests separately prove that local bytes are reserved before cluster admission and released after provider failure. Resumable-upload tests prove that a rejected tenant-scoped staging reservation does not demand the part body and releases its durable ledger reservation.

See [Configuration Reference](../guide/configuration-reference.md), [Multi-Tenant Storage](../runtime/multi-tenancy.md), and [Performance](../ops/performance.md).
