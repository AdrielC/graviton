# Roadmap

Graviton is an operational pre-1.0 content-addressable storage runtime. This roadmap separates repository-complete functionality from deployment-specific qualification and future product work.

## 0.6 release boundary

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
- Shardcake upload locality with typed ZIO Blocks wire codecs, stable session ownership, real two-node reassignment proof, and native ZIO Metrics health observations
- Crash-safe resumable HTTP sessions with durable filesystem or PostgreSQL ledgers, streamed filesystem or S3 staging, idempotent bounded parts, expiry cleanup, and a typed JVM SDK
- Deterministic rendezvous block placement across declared failure domains, safe-by-default write quorum, validating reads, atomic corrupt-replica replacement, and supervised bounded repair cycles
- Independently addressed S3 or Ceph RGW targets, locality-aware reads, fixed 2+1 erasure coding, original-key verification, destructive single-target volume-loss qualification, and live Prometheus and Grafana SLO surfaces
- PDF-aware chunker selection through bounded media sniffing and scoped ZIO services, with the reusable `graviton-pdf` adapter consuming `zio-pdf`
- Operator console backed by live storage and Shardcake state, using ZIO Blocks DataStar actions and the official local browser runtime
- Hardened two-node Compose bundle with strict config validation, immutable images, coordinated backup, isolated restore, 90-day retained benchmark distributions, rolling upgrade and rollback proof, sustained backend failure drills, container SBOM, provenance, and keyless image signing
- Backend-native opaque cursor pagination and complete streaming inventory for filesystem, PostgreSQL, S3, HTTP, gRPC, CLI, and the Scala SDK
- Typed `StoreError` channels across blob, block, manifest, locator-object, maintenance, and garbage-collection ports
- Process-wide weighted transfer-memory admission plus interruption-safe release tests
- Filesystem and PostgreSQL repair journals with durable cursors, attempt counts, resolution, and streamed dead letters
- Published `graviton-backend-laws` ZIO Test contract, self-applied to in-memory and filesystem CAS implementations
- Published deterministic CrashLab and tenant-storage laws, default-isolated FiberRef routing, explicit shared-dedup policy, and domain-wide streaming GC marks

## Operator acceptance

These items depend on the deployment and cannot be completed once for every user inside this repository.

- Validate OIDC claims, capabilities, JWKS reachability, proxy headers, TLS termination, CORS, and gRPC ingress against the real identity provider and network
- Run the restore drill from real backups and record recovery time and recovery point objectives
- Run `scripts/benchmark-suite.sh` and `scripts/soak-http.sh` with representative objects, concurrency, backends, and retention settings; repository-generated corpus results are regression evidence, not target capacity
- Run `scripts/qualify-local-shardcake.sh`, `scripts/qualify-rolling-upgrade.sh`, `scripts/qualify-long-failure.sh`, and `scripts/qualify-three-domain.sh`, then repeat storage throttling, credential rotation, target loss, and rollback against the exact target images and infrastructure
- For shared S3 plus PostgreSQL, retain the exact version-pair rolling record before claiming high availability

## Next product work

### Protocols and clients

- Add optional gRPC range reads and server-side verification if production consumers need parity with those HTTP extensions
- Define an explicit idempotency-key contract for non-content-derived operations
- Retain target-scale multi-tenant HTTP and gRPC envelopes across tenant cardinality, payload distributions, and noisy-neighbor workloads
- Add scheduled domain-wide replica scrubbing and shared-domain membership snapshots to the packaged multi-tenant service
- Integrate a distributed contractual rate limiter while retaining the process-local limiter for load shedding

### Storage and reliability

- Add long-duration power-loss and partial-write fault injection for filesystem, PostgreSQL, and S3
- Add operator-facing inventory and restore commands for S3 quarantine records
- Add durable tenant-catalog snapshots so shared-domain maintenance can prove the complete manifest set across processes
- Evaluate compression and authenticated encryption only with complete encode/decode, key-provider, migration, and content-identity semantics
- Add a RocksDB `BlockStore` only when a real embedded blob-backend use case requires it; the published RocksDB module currently promises durable typed key-value storage

### Operations

- Publish portable benchmark envelopes only after multiple controlled operator environments produce retained raw samples
- Add backend-specific latency distributions and durable long-term dashboard retention
- Add signed migration sequencing beyond the initial schema ledger
- Retain more exact release-pair upgrade records as storage formats and dependencies evolve

## Compatibility stance

The 0.x line may make intentional breaking changes in minor releases. The runnable server exposes only the canonical `/api/v1/blobs` contract, with no legacy HTTP aliases or legacy product modules. Public Scala APIs are checked against the previous release with `sbt-version-policy`; content-key or committed-frame changes require an explicit new format contract before implementation.
