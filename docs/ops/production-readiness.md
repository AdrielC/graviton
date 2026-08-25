# Production Readiness

Graviton 0.2 is a production candidate for controlled embedded and single-node filesystem use. Its shared S3 plus PostgreSQL path is integration-tested and suitable for environment qualification. It is not a universal high-availability claim.

## Support matrix

| Area | Implemented and tested | Deployment boundary |
| --- | --- | --- |
| Embedded runtime | In-memory and filesystem CAS, streaming ingest/read, deduplication, inspect, verify, and delete | Application owns lifecycle, capacity, backup, and access control |
| Filesystem server | Fsync, atomic publication, readiness, versioned HTTP, auth policy, audit, backup and restore drill, reversible GC | One writer process per data root; use `Recreate`, not rolling replicas |
| S3 plus PostgreSQL | Real MinIO/PostgreSQL CI, backend readiness, retries, replica index, S3 quarantine/restore API | Qualify provider semantics, migrations, concurrent processes, backup, and rollback |
| Replication | Parallel writes, configurable quorum, validating fallback reads, repair, and health | Library primitive; automatic scheduling and placement policy are not mounted in `Main` |
| HTTP v1 | Upload, inventory, pagination, metadata, verify, GET, HEAD, ranges, preconditions, and delete | Multipart and resumable sessions are not implemented |
| Scala SDK | Typed streaming upload/download plus list, metadata, verify, ranges, and delete; logical 1 TiB contract and real 32 MiB socket round trip | Physically qualify target object sizes, timeouts, and memory under production concurrency |
| Security | RS256 OIDC/JWKS, issuer/audience checks, capabilities, rate and size controls, exact origins, trusted proxy policy, audit chain | Operator must configure and test the real IdP, ingress, TLS, proxy trust, and retention |
| Packaging | Fat JAR, distroless non-root image, Kubernetes example, SBOM, checksums, attestations | Maven Central requires repository signing credentials |
| gRPC | Contracts, generated stubs, clients, and adapters | No runnable gRPC listener in the packaged server yet |
| RocksDB | Durable key-value adapter | Not a complete CAS `BlockStore` backend |

## Executable release gates

Run these from a clean clone:

```bash
TESTCONTAINERS=0 ./sbt scalafmtCheckAll test
GRAVITON_IT=1 ./sbt "server/testOnly graviton.server.EmbeddedPgFsCasRoundTripSpec"
./scripts/verify-external-consumer.sh
./sbt server/assembly
./scripts/smoke-packaged-server.sh
./sbt docs/mdoc checkDocSnippets buildDocsAssets
npm ci --prefix docs
npm run docs:build --prefix docs
```

CI additionally starts pinned PostgreSQL and MinIO images. The packaged smoke runs actual open and authenticated server processes. It uploads and compares bytes, tests range and conditional responses, verifies stored content, checks anonymous rejection, and checks capability denial.

The SDK tests also execute a real 32 MiB upload and download through the ZIO HTTP client, streaming server, and CAS, then compare the incremental digest and run metadata, inventory, and verification calls. A separate logical 1 TiB test proves `Long` length handling, source laziness, and absence of materialized request content. It does not physically transfer 1 TiB.

The direct S3 blob adapter uses adaptive multipart targets that begin at 5 MiB and grow every 256 parts to a hard, Iron-enforced 128 MiB buffer ceiling. Its 10,000-part schedule has more than 1 TiB of logical capacity. This is a bounded-memory and capacity proof, not a physical 1 TiB S3 transfer or a concurrency sizing result.

## Deployment profiles

### Embedded

Use `Graviton.inMemory` for ephemeral tests and `Graviton.fs` for a durable application-owned store. Do not share one filesystem root between uncoordinated writer processes.

### Single-node filesystem service

Use one process, one writable volume, backend readiness probes, and a `Recreate` rollout. Stop writes or snapshot the volume atomically before backup. Run the restore drill on every backup class. Preview garbage collection before quarantine and retain quarantine long enough to restore errors.

### Shared S3 plus PostgreSQL

Use provider-native object durability, an existing bucket, PostgreSQL migrations, encrypted connections, least-privilege credentials, and coordinated backups. The repository proves real adapter integration, not a particular provider service level. Run concurrent upload, rolling restart, credential rotation, database failover, and object-store outage tests before declaring the deployment highly available.

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

`/api/health/live` proves the process is alive. `/api/health/ready` checks the active storage composition with a timeout. `/api/stats` and `/metrics` expose process-local counters plus HTTP request, error, and latency observations. When security is enabled, stats and metrics require `observability.read`.

Treat these as operational signals, not a capacity guarantee. Alerts and service levels must be derived from the target workload and retained measurements.

## Release integrity

A `v*` tag validates the build, runs the packaged smoke, proves external consumption, creates checksums and an SPDX SBOM, attests the JAR and container, pushes a multi-architecture GHCR image, and creates a GitHub release. Signed Maven Central publication runs only when Sonatype and PGP secrets are configured.

## Remaining product work

The main product gaps are runnable gRPC parity, a full RocksDB block backend, resumable uploads, automatic replica scheduling, long-duration fault injection, and a qualified multi-process upgrade story. These are tracked in the root `ROADMAP.md` without downgrading the functionality that already works.
