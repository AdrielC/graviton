# Roadmap

Graviton is an operational pre-1.0 content-addressable storage runtime. This roadmap separates repository-complete functionality from deployment-specific qualification and future product work.

## 0.3 release boundary

- Typed, bounded streaming APIs across HTTP, gRPC, filesystem, PostgreSQL, and S3-compatible storage
- Canonical `/api/v1/blobs` upload, inventory, metadata, verification, retrieval, range, precondition, and delete lifecycle
- Packaged gRPC listener with client-streaming upload, server-streaming download and inspection, stat, list, delete, and public backend health
- Typed Scala SDKs using ZIO Streams, ZIO Blocks media types, Iron-refined byte limits, and transparent stream-scoped transport state
- Operational PostgreSQL key-value and chunked object stores with transactional commit, rollback, copy, list, range tracking, and streaming reads
- Operational S3 content-addressed and generic object adapters with bounded multipart buffering, retry-safe publication, and explicit multipart abort finalizers
- Durable RocksDB typed key-value adapter with close-and-reopen persistence coverage
- Filesystem CAS with fsync, atomic publication, restart-safe manifests, conservative garbage collection, quarantine, and restore
- Backend-wide shared/exclusive maintenance coordination for complete blob-operation stream lifetimes and garbage collection, implemented with filesystem locks and PostgreSQL advisory locks
- RS256 OIDC/JWKS verification, capability authorization, trusted-proxy TLS policy, exact CORS origins, request and byte limits, gRPC interceptors, and hash-chained audit events
- Clean external-consumer resolution for every published module plus a JAR-content gate that rejects empty or unsupported-operation artifacts
- Packaged JAR smoke proof, non-root container, Kubernetes and on-prem examples, backup/restore tooling, SPDX SBOM, checksums, attestations, and signed Maven Central publication

## Operator acceptance

These items depend on the deployment and cannot be completed once for every user inside this repository.

- Validate OIDC claims, capabilities, JWKS reachability, proxy headers, TLS termination, CORS, and gRPC ingress against the real identity provider and network
- Run the restore drill from real backups and record recovery time and recovery point objectives
- Run `scripts/benchmark-http.sh` and `scripts/soak-http.sh` with representative objects, concurrency, backends, and retention settings
- Exercise node termination, storage throttling, credential rotation, backend outages, and rollback in the target environment
- For shared S3 plus PostgreSQL, qualify concurrent processes and rolling upgrades before claiming high availability

## Next product work

### Protocols and clients

- Add resumable HTTP upload contracts with restart, retry, expiry, and idempotency acceptance suites
- Add optional gRPC range reads and server-side verification if production consumers need parity with those HTTP extensions
- Define an explicit idempotency-key contract for non-content-derived operations

### Storage and reliability

- Schedule replica scrub and repair jobs around `ReplicatedBlockStore` and `PgReplicaIndex`
- Add long-duration power-loss and partial-write fault injection for filesystem, PostgreSQL, and S3
- Add operator-facing inventory and restore commands for S3 quarantine records
- Evaluate compression and authenticated encryption only with complete encode/decode, key-provider, migration, and content-identity semantics
- Add a RocksDB `BlockStore` only when a real embedded blob-backend use case requires it; the published RocksDB module currently promises durable typed key-value storage

### Operations

- Add a coordinated backup snapshot command that quiesces built-in writers while manifest and block snapshots are established
- Publish benchmark envelopes after multiple controlled environments produce retained raw samples
- Add dashboards and backend-specific latency distributions
- Add signed migration sequencing beyond the initial schema ledger
- Document and test multi-process zero-downtime upgrades

## Compatibility stance

The 0.x line may make breaking changes only in documented minor releases. Content keys and committed framed manifests require explicit migration or backward readers. Public Scala APIs are checked against the previous release with `sbt-version-policy`. Deprecated HTTP aliases remain until a documented removal release.
