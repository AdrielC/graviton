# Roadmap

Graviton is an operational pre-1.0 CAS runtime. This roadmap records what the 0.1 release candidate actually proves and orders the work that still changes the support boundary.

## 0.1 release candidate

- Stable module organization plus source and binary compatibility checks
- Clean external sbt consumer proof against locally published POMs
- Canonical `/api/v1/blobs` lifecycle with structured errors and deprecated legacy aliases
- Cursor pagination, byte ranges, ETags, modification dates, and HTTP preconditions
- RS256 OIDC/JWKS verification with issuer, audience, algorithm, and key validation
- Capability authorization, trusted-proxy TLS policy, exact CORS origins, request and byte limits, and audit chains
- Fsync plus atomic filesystem publication, readiness checks, S3 client retries, and strict duplicate validation
- PostgreSQL replica-index persistence and migration checksum ledger
- Parallel block replication with write quorums, validating read fallback, repair, and health checks
- Two-pass unreachable-block collection with minimum age, dry-run, quarantine, restore, and delayed purge APIs
- Packaged JAR smoke proof, non-root container, conservative Kubernetes example, backup/restore tooling, soak tooling, and provenance-rich measurement output
- Pinned CI actions, dependency review, dependency submission, CodeQL for supported workflow code, Dependabot, SPDX SBOM, checksums, and artifact attestations

## 0.1 operator acceptance

These items are deployment-specific. They cannot be completed once for every user inside the repository.

- Validate OIDC claims, capabilities, JWKS reachability, proxy headers, TLS termination, and CORS against the actual ingress and identity provider
- Run the restore drill from real backups and record recovery time and recovery point objectives
- Run `scripts/benchmark-http.sh` and `scripts/soak-http.sh` with representative data, concurrency, backends, and retention settings
- Exercise node termination, storage throttling, credential rotation, backend outages, and rollback in the target environment
- For shared S3 plus PostgreSQL, qualify concurrent processes and rolling upgrades before claiming high availability

## 0.2 priorities

### Protocols and clients

- Wire the generated gRPC services into the packaged server and reach HTTP lifecycle parity
- Add resumable and multipart upload contracts with restart and retry acceptance suites
- Publish narrow Scala client artifacts once the first public coordinates are available
- Define an explicit idempotency-key contract for non-content-derived operations

### Storage and reliability

- Promote the RocksDB adapter into a complete CAS `BlockStore`
- Schedule replica scrub and repair jobs around `ReplicatedBlockStore` and `PgReplicaIndex`
- Add long-duration power-loss and partial-write fault injection for filesystem, PostgreSQL, and S3
- Add operator-facing inventory and restore commands for S3 quarantine records
- Evaluate compression and authenticated encryption without changing content identity semantics

### Operations

- Publish benchmark envelopes only after multiple controlled environments produce retained raw samples
- Add dashboards and backend-specific latency distributions
- Add signed migration sequencing beyond the initial schema ledger
- Document and test multi-process zero-downtime upgrades

## Compatibility stance

The 0.x line may make breaking changes only in documented minor releases. Content keys and committed framed manifests require explicit migration or backward readers. Public Scala APIs are checked against the previous release with `sbt-version-policy`. Deprecated HTTP aliases remain until a documented removal release.
