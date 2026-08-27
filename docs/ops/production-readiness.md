# Production Readiness

Graviton 0.5 is a production candidate for controlled embedded and single-node filesystem use. Its shared S3 plus PostgreSQL path is integration-tested and suitable for environment qualification. It is not a universal high-availability claim.

## Support matrix

| Area | Implemented and tested | Deployment boundary |
| --- | --- | --- |
| Embedded runtime | In-memory and filesystem CAS, bounded-queue ingest, incremental manifests, verified streaming reads, deduplication, inspect, verify, and delete | Application owns lifecycle, capacity, backup, and access control |
| Filesystem server | Fsync, atomic publication, readiness, versioned HTTP, auth policy, audit, backup and restore drill, exact streamed reversible GC, and cross-process file-lock coordination | Every process must use the built-in coordinator against the same data root; qualify shared-volume lock semantics before overlapping replicas |
| S3 plus PostgreSQL | Real MinIO/PostgreSQL CI, backend readiness, retries, replica index, S3 quarantine/restore API, and PostgreSQL advisory-lock coordination | Use one maintenance namespace per repository; qualify provider semantics, migrations, concurrent processes, backup, and rollback |
| Replication | Parallel writes, configurable quorum, validating fallback reads, repair, and health | Library primitive; automatic scheduling and placement policy are not mounted in `Main` |
| HTTP v1 | Upload, inventory, pagination, metadata, verify, GET, HEAD, ranges, preconditions, and delete | Multipart and resumable sessions are not implemented |
| Upload locality | Shardcake 2.8.1 session ownership, direct one-shot owner streaming, bounded hot state, durable PostgreSQL placement, authenticated internode control, and node-drain reassignment | Opt-in; target clusters still require workload and rolling-upgrade qualification |
| Scala SDK | Typed streaming upload/download plus list, metadata, verify, ranges, and delete; logical 1 TiB contract and real 32 MiB socket round trip | Physically qualify target object sizes, timeouts, and memory under production concurrency |
| Security | RS256 OIDC/JWKS, issuer/audience checks, capabilities, rate and size controls, exact origins, trusted proxy policy, audit chain | Operator must configure and test the real IdP, ingress, TLS, proxy trust, and retention |
| Packaging | Fat JAR, distroless non-root image, Kubernetes example, SBOM, checksums, attestations, and Maven Central publication | Release secrets and target repositories remain operator-owned |
| gRPC | Packaged listener, generated stubs, bounded streaming client, lifecycle calls, auth, capabilities, rate limiting, and audit | HTTP additionally supports ranges, preconditions, and verification |
| RocksDB | Durable key-value adapter | Not a complete CAS `BlockStore` backend |

## Executable release gates

Run these from a clean clone:

```bash
TESTCONTAINERS=0 ./sbt scalafmtCheckAll test
GRAVITON_IT=1 ./sbt "server/testOnly graviton.server.EmbeddedPgFsCasRoundTripSpec"
./scripts/verify-external-consumer.sh
./scripts/audit-published-artifacts.sh
./sbt server/assembly
./scripts/smoke-packaged-server.sh
./sbt docs/mdoc checkDocSnippets buildDocsAssets
npm ci --prefix docs
npm run docs:build --prefix docs
```

CI additionally starts pinned PostgreSQL and MinIO images. The packaged smoke runs actual open and authenticated server processes. It uploads and compares HTTP bytes, tests range and conditional responses, verifies stored content, checks anonymous rejection, and checks capability denial. It also streams 3 MiB through both open and authenticated gRPC listeners in the assembled JAR, validates every byte without collecting the payload, and exercises health, stat, list, inspect, and delete.

The SDK tests also execute a real 32 MiB upload and download through the ZIO HTTP client, streaming server, and CAS, then compare the incremental digest and run metadata, inventory, and verification calls. A separate logical 1 TiB test proves `Long` length handling, source laziness, and absence of materialized request content. It does not physically transfer 1 TiB.

The gRPC suite starts the real listener on an ephemeral port and streams 12 MiB through generated client and server stubs before exercising stat, list, inspect, and delete. Frames are capped at 1 MiB and checked for contiguous download offsets.

The direct S3 blob adapter uses adaptive multipart targets that begin at 5 MiB and grow every 256 parts to a hard, Iron-enforced 128 MiB buffer ceiling. Its 10,000-part schedule has more than 1 TiB of logical capacity. Once the digest is known, objects above the single-copy threshold are promoted to their content key with server-side multipart copy in 512 MiB ranges, and failed source or destination multipart uploads are explicitly aborted. The suite executes the complete 1 TiB copy plan against a recording S3 client without allocating payload bytes. This is a bounded-memory protocol and capacity proof, not a physical 1 TiB S3 transfer or a concurrency sizing result.

## Deployment profiles

### Embedded

Use `Graviton.inMemory` for ephemeral tests and `Graviton.fs` for a durable application-owned store. `Graviton.fs` coordinates complete blob operations and maintenance through a lock file inside the repository. Do not mix it with raw, uncoordinated store construction against the same root.

### Single-node filesystem service

Use one writable volume, backend readiness probes, and a conservative `Recreate` rollout. The built-in server and CLI coordinate through `<root>/cas/.maintenance.lock`; every process must use the same root and a filesystem whose file locks work across its clients. Stop writes or snapshot the volume atomically before backup. Run the restore drill on every backup class. Preview garbage collection before quarantine and retain quarantine long enough to restore errors.

### Shared S3 plus PostgreSQL

Use provider-native object durability, an existing bucket, PostgreSQL migrations, encrypted connections, least-privilege credentials, and coordinated backups. Configure the same `GRAVITON_MAINTENANCE_NAMESPACE` in every process that reaches the repository. Shared blob operations and exclusive maintenance use PostgreSQL session advisory locks. Drain traffic or retry if sustained activity from another process makes exclusive acquisition time out. The repository proves real adapter integration, not a particular provider service level. Run concurrent upload, rolling restart, credential rotation, database failover, and object-store outage tests before declaring the deployment highly available.

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

Filesystem writes force file contents before atomic publication and sync parent directories. Manifest replacement has no non-atomic fallback. S3 checks only true not-found responses as absence and validates duplicate content. PostgreSQL writes use transactions.

Those properties do not replace environment tests. Each deployment still needs abrupt termination, full disk, read-only filesystem, network timeout, expired credentials, PostgreSQL failover, restore, and rollback exercises.

## Observability

`/api/health/live` proves the process is alive. `/api/health/ready` checks the active storage composition with a timeout and, when upload locality is enabled, fails until the local node owns a Shardcake assignment. `/api/stats` and `/metrics` expose process-local counters plus HTTP request, error, and latency observations. When security is enabled, stats and metrics require `observability.read`.

Treat these as operational signals, not a capacity guarantee. Alerts and service levels must be derived from the target workload and retained measurements.

## Release integrity

A `v*` tag validates the build, runs the packaged smoke, proves external consumption, creates checksums and an SPDX SBOM, attests the JAR and container, pushes a multi-architecture GHCR image, publishes signed Maven Central modules, and creates a GitHub release. Missing or invalid Sonatype and PGP credentials fail the release instead of silently omitting publication.

## Remaining product work

The main product gaps are resumable HTTP uploads, automatic replica scheduling, coordinated backup snapshots, long-duration fault injection, and a qualified multi-process upgrade story. RocksDB is deliberately scoped as a durable key-value module rather than advertised as a blob backend. These boundaries are tracked in the root `ROADMAP.md` without downgrading the functionality that already works.
