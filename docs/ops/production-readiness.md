# Production Readiness

Current Graviton main is a production candidate for controlled embedded and single-node filesystem use. Its shared S3 plus PostgreSQL and two-node Shardcake path is integration-tested, fault-drilled locally, and packaged for retained workload, mixed-version, rollback, and sustained-failure qualification. It is not a universal high-availability claim.

## Support matrix

| Area | Implemented and tested | Deployment boundary |
| --- | --- | --- |
| Embedded runtime | In-memory and filesystem CAS, bounded-queue ingest, incremental manifests, verified streaming reads, deduplication, inspect, verify, and delete | Application owns lifecycle, capacity, backup, and access control |
| Filesystem server | Fsync, atomic publication, readiness, versioned HTTP, auth policy, audit, backup and restore drill, exact streamed reversible GC, and cross-process file-lock coordination | Every process must use the built-in coordinator against the same data root; qualify shared-volume lock semantics before overlapping replicas |
| S3 plus PostgreSQL | Real MinIO/PostgreSQL CI, backend readiness, retries, replica index, S3 quarantine/restore API, and PostgreSQL advisory-lock coordination | Use one maintenance namespace per repository; qualify provider semantics, migrations, concurrent processes, backup, and rollback |
| Replication | Failure-domain rendezvous placement, safe-by-default quorum writes, validating fallback reads, atomic corrupt-copy replacement, scoped bounded repair, and metrics | Operators must declare genuinely independent targets and retain target outage evidence |
| 2+1 erasure | Three independently configured S3-compatible targets, any-two reconstruction, per-shard proof, original CAS verification, bounded repair, and destructive-volume qualification | Tolerates one target loss only; target names and failure domains are part of the physical layout |
| HTTP v1 | Direct upload plus durable resumable create, status, idempotent part, commit, cancel, inventory, metadata, verify, GET, HEAD, ranges, preconditions, and delete | Physically qualify target maximums and staging lifecycle |
| Upload locality | Shardcake 2.8.1 session ownership, direct one-shot owner streaming, bounded hot state, durable PostgreSQL placement, authenticated internode control, and node-drain reassignment | Opt-in; target clusters still require workload and rolling-upgrade qualification |
| Scala SDK | Typed direct and resumable upload, durable checkpoint callback, bounded retries, download, list, metadata, verify, ranges, and delete; logical 1 TiB contract plus direct and resumable socket proofs | Application must durably save checkpoints and reopen sources at exact offsets for process recovery |
| Security | RS256 OIDC/JWKS, issuer/audience checks, capabilities, rate and size controls, exact origins, trusted proxy policy, audit chain | Operator must configure and test the real IdP, ingress, TLS, proxy trust, and retention |
| Packaging | Fat JAR, distroless non-root image, Kubernetes example, SBOM, checksums, attestations, and Maven Central publication | Release secrets and target repositories remain operator-owned |
| gRPC | Packaged listener, generated stubs, bounded streaming client, lifecycle calls, auth, capabilities, rate limiting, and audit | HTTP additionally supports ranges, preconditions, and verification |
| RocksDB | Durable key-value adapter | Not a complete CAS `BlockStore` backend |
| Storage contracts | Typed operation-specific `StoreError`, native opaque cursor pages, complete lazy inventory, published behavior, crash, tenant, and streaming laws | Third-party adapters must run the same laws and retain backend-specific fault evidence |
| Multi-tenant contracts | Authenticated HTTP and gRPC binding, durable cell-scoped policy, private manifests, forced PostgreSQL RLS, isolated or explicit shared domains, cluster-atomic retained quotas, bounded sharded admission, a connection-coalescing cell maintenance barrier, tenant laws, and domain-wide GC marks | Exclusive maintenance drains a cell; in-process faults are not physical power loss; global request limits, target IdP acceptance, billing, and target-scale capacity remain deployment work |
| Transfer admission | Composable named footprints with process-byte, tenant-byte and concurrency, and backend-concurrency admission | Size every hierarchy against heap, direct memory, tenant policy, pools, and backend capacity |
| Manifest authentication | Versioned keyed proof over blob identity, chunker, ordered block keys, and spans; verified before block fetch | Enable required mode, manage rotation keys, and keep HMAC or external signer material in a secret manager |
| Repair progress | Durable filesystem or PostgreSQL cursor plus streamed unresolved-failure records | Alert and operate the dead-letter stream; a journal does not repair an unavailable target by itself |

## Executable release gates

Run these from a clean clone:

```bash
TESTCONTAINERS=0 ./sbt scalafmtCheckAll test
GRAVITON_IT=1 ./sbt "server/testOnly graviton.server.EmbeddedPgFsCasRoundTripSpec"
./scripts/qualify-multitenant.sh
./scripts/verify-external-consumer.sh
./scripts/audit-published-artifacts.sh
./sbt server/assembly
./scripts/smoke-packaged-server.sh
npm ci --prefix docs
./sbt docs/mdoc checkDocSnippets buildDocsAssets
npm run docs:build --prefix docs
```

CI additionally starts pinned PostgreSQL and MinIO images. The packaged smoke runs actual open and authenticated server processes. It uploads and compares HTTP bytes, tests range and conditional responses, verifies stored content, checks anonymous rejection, and checks capability denial. It also streams 3 MiB through both open and authenticated gRPC listeners in the assembled JAR, validates every byte without collecting the payload, and exercises health, stat, list, inspect, and delete.

The scheduled `Production qualification` workflow builds the exact latest-release baseline and candidate images, enables manifest authentication with a masked ephemeral key, runs the local two-node failure proof, and then executes matching clean-state multi-tenant cohorts on the same runner. The retained gate compares p50, p95, and p99 upload latency, aggregate throughput, heap high-water, GC pause, PostgreSQL waiters, S3 retries, Jain tenant fairness, and repair backlog when the topology has a convergent replica service. It runs the exact version pair through an explicitly unsigned compatibility cohort because v0.7.0 predates manifest proofs. That transitional state is then destroyed before the strict candidate-only topology begins the ten-minute sustained-failure workload. Required mode never accepts unsigned manifests. A separate job retains destructive three-domain erasure evidence. Results are commit-addressed for 90 days and apply only to that runner and topology.

The SDK tests also execute a real 32 MiB upload and download through the ZIO HTTP client, streaming server, and CAS, then compare the incremental digest and run metadata, inventory, and verification calls. A separate logical 1 TiB test proves `Long` length handling, source laziness, and absence of materialized request content. It does not physically transfer 1 TiB.

The gRPC suite starts the real listener on an ephemeral port and streams 12 MiB through generated client and server stubs before exercising stat, list, inspect, and delete. Frames are capped at 1 MiB and checked for contiguous download offsets.

Resumable acceptance proves incremental over-limit short circuit, interruption finalizers, TestClock expiry, idempotent retry without body pull, process reconstruction, post-commit cleanup recovery, real filesystem staging, real PostgreSQL state, HTTP offset conflicts, three-part JVM SDK upload, CAS commit, and byte-exact download. The scheduled qualification then keeps the candidate under a resumable workload while stopping each node, restarting the Shardcake manager, and taking MinIO and PostgreSQL offline repeatedly.

Replication acceptance writes two physical filesystem copies, corrupts one on disk, starts a reconstructed packaged layer, and waits for the supervised manifest scrub to validate and atomically restore it before any read. Separate tests cover stable failure-domain placement and failed quorum behavior.

The direct S3 blob adapter uses adaptive multipart targets that begin at 5 MiB and grow every 256 parts to a hard, Iron-enforced 128 MiB buffer ceiling. Its 10,000-part schedule has more than 1 TiB of logical capacity. Once the digest is known, objects above the single-copy threshold are promoted to their content key with server-side multipart copy in 512 MiB ranges, and failed source or destination multipart uploads are explicitly aborted. The suite executes the complete 1 TiB copy plan against a recording S3 client without allocating payload bytes. This is a bounded-memory protocol and capacity proof, not a physical 1 TiB S3 transfer or a concurrency sizing result.

## Deployment profiles

### Embedded

Use `Graviton.inMemory` for ephemeral tests and `Graviton.fs` for a durable application-owned store. `Graviton.fs` coordinates complete blob operations and maintenance through a lock file inside the repository. Do not mix it with raw, uncoordinated store construction against the same root.

### Single-node filesystem service

Use one writable volume, backend readiness probes, and a conservative `Recreate` rollout. The built-in server and CLI coordinate through `<root>/cas/.maintenance.lock`; every process must use the same root and a filesystem whose file locks work across its clients. Stop writes or snapshot the volume atomically before backup. Run the restore drill on every backup class. Preview garbage collection before quarantine and retain quarantine long enough to restore errors.

### Shared S3 plus PostgreSQL

Use provider-native object durability, existing staging and block buckets, PostgreSQL migrations, encrypted connections, least-privilege credentials, and coordinated backups. The production Compose topology separates the bootstrap owner from a `NOSUPERUSER NOBYPASSRLS` runtime role and denies the runtime role tenant-policy writes. Configure the same `GRAVITON_MAINTENANCE_NAMESPACE` in every process that reaches the repository. Shared blob operations and exclusive maintenance use PostgreSQL session advisory locks. Drain traffic or retry if sustained activity from another process makes exclusive acquisition time out. The repository proves real adapter integration and supplies exact rolling and failure harnesses, not a particular provider service level. Re-run them with the target image pair, credentials, network, database, and object store before declaring the deployment highly available.

### Packaged multi-tenant service

Set `GRAVITON_MULTI_TENANT_ENABLED=true` only with production OIDC, TLS enforcement, JDBC audit, PostgreSQL, and S3-compatible storage. The verified token organization selects a server-owned, cell-scoped policy. Keep the default isolated route for unrelated customers. Shared dedup domains are appropriate only for explicit trust groups because duplicate-write observations expose a content-membership signal. One cell-wide coordinator coalesces ordinary operations onto one shared advisory-lock session per process; an exclusive maintenance lease drains the cell. Use separate deployment cells, credentials, buckets, databases, and encryption policy when a customer's contract requires a silo boundary or a smaller maintenance blast radius. See [Multi-tenant storage](../runtime/multi-tenancy.md).

## Security acceptance

Production deployments must not set `GRAVITON_SECURITY_DEV_SHARED_SECRET`. Configure an HTTPS JWKS URI and verify all of the following in the target environment:

- only RS256 tokens from the configured issuer and audience are accepted
- the ingress overwrites untrusted forwarding headers before `GRAVITON_SECURITY_TRUST_PROXY_HEADERS=true`
- TLS is required at the correct trust boundary
- capability masks match the intended identities and resources
- request, upload-byte, and download-byte limits match real workloads
- CORS origins are exact and minimal
- JDBC audit storage is retained, backed up, and monitored if durable audit evidence is required
- secrets never appear in manifests, command arguments, logs, or benchmark output

## Durability acceptance

Filesystem writes force file contents before atomic publication and sync parent directories. Manifest and resumable-ledger replacement has no non-atomic fallback. S3 checks only true not-found responses as absence, validates duplicate content, aborts interrupted staging multipart uploads, and atomically replaces corrupt repair targets. PostgreSQL manifest and resumable transitions use transactions and row locks.

Those properties do not replace environment tests. Each deployment still needs abrupt termination, full disk, read-only filesystem, network timeout, expired credentials, PostgreSQL failover, restore, and rollback exercises.

## Observability

`/api/health/live` proves the process is alive. `/api/health/ready` checks the active block, manifest, resumable-ledger, and staging composition with a timeout and, when upload locality is enabled, fails until the local node owns a Shardcake assignment. `/api/stats` and `/metrics` expose process-local counters plus HTTP request, error, latency, JVM, S3 API call and retry, and repair observations. PostgreSQL deployments also expose fixed-cardinality pool gauges for active, idle, total, maximum, and waiting connections, labeled `primary` and `shardcake` where both pools are active. When security is enabled, stats and metrics require `observability.read`.

`deploy/three-domain` ships live Prometheus recording and alert rules plus a provisioned Grafana dashboard for HTTP success ratio, p99 latency, erasure target health, read locality, and repair backlog. These read the server's actual `/metrics` endpoint. They are an operational baseline, not a capacity guarantee. Tune service levels from target workload and retained measurements.

## Release integrity

A `v*` tag validates the build, runs the packaged smoke, proves external consumption, creates checksums and SPDX SBOMs for the JAR and image, attests the JAR and container, signs the immutable multi-architecture GHCR digest with GitHub OIDC through Sigstore, publishes signed Maven Central modules, and creates a GitHub release. Missing or invalid Sonatype and PGP credentials fail the release instead of silently omitting publication.

## Remaining product work

The main product gaps are scheduled multi-tenant domain scrubbing, durable shared-domain membership snapshots, distributed contractual request limiting, physical power-loss and disk-corruption injection, retained multi-tenant benchmark envelopes from multiple operator environments, and acceptance against each real IdP, ingress, Ceph cluster, and storage provider. The repository now provides authenticated tenant binding, cluster-atomic retained quotas, pooled connection bounds, resumable uploads, durable repair progress and dead letters, automatic durability orchestration, in-process CrashLab laws, destructive single-target volume-loss evidence, mixed-version rollback qualification, and a sustained outage drill, but those cannot make every deployment topology equivalent. RocksDB remains a durable key-value module rather than an advertised blob backend.
