# Roadmap

Graviton is a pre-1.0 content-addressed byte-storage runtime. The [implementation status ledger](docs/implementation-status.md) is the source of truth for what is released, what exists only on `main`, and what still requires target qualification.

## Release the current main line

`v0.7.0` is the latest Maven Central and GitHub release. Current `main` adds multi-tenant hardening, distributed Redis or Valkey admission, GVM4 metadata and manifest authentication, tenant-domain snapshots, operator APIs, and Production Qualification and Telemetry v1. Those additions need a later tag and successful release workflow before downstream builds can consume them from Maven Central.

Before that release:

- publish the current main line as `v0.8.0` only after its exact commit passes CI;
- keep source and binary compatibility checks green against `v0.7.0`;
- run the clean external consumer and published-JAR audit;
- run packaged HTTP and gRPC smoke tests;
- retain filesystem backup and restore proof;
- confirm every machine-readable qualification and observability contract;
- state the clean-store GVM4 and PostgreSQL V001 boundaries in the release notes.

## Code gaps

These are implementation gaps, not shipped capabilities:

- Add a complete RocksDB `BlockStore` only if an embedded CAS use case justifies it. `graviton-rocks` currently provides typed key-value storage only.
- Add page extraction, semantic PDF chunking, malware policy, or content enrichment only as separate, bounded integrations. Current PDF-aware ingest validates the signature and chooses structural block boundaries.
- Remove or quarantine the unbuilt `modules/core`, `modules/db`, and `modules/pg` source trees after confirming that no still-useful experiment is lost.
- Replace the compatibility fallback implementations of manifest inspection and quarantine inventory before advertising a third-party backend as production supported.

## Target qualification

These results cannot be completed once for every operator inside repository CI:

- validate the exact OIDC claims, capabilities, JWKS behavior, TLS termination, trusted-proxy headers, and CORS origins;
- retain load and 60-minute-or-longer soak results for representative objects, concurrency, tenant mix, and deduplication distribution;
- exercise Redis or Valkey failover and partitions, database failover, S3 throttling and timeouts, zone impairment, and provider backup restore;
- inject abrupt termination, physical power loss, disk corruption, full disk, read-only filesystem, and expired credentials;
- qualify Ceph RGW or each selected S3 provider rather than extrapolating from MinIO;
- record recovery time, recovery point, latency, throughput, memory high-water, fairness, and repair backlog for the exact image and infrastructure.

Use these existing entry points:

```bash
./scripts/graviton-operator doctor
./scripts/graviton-operator qualify
./scripts/qualify-node-replacement.sh
./scripts/qualify-long-failure.sh
./scripts/qualify-three-domain.sh
./scripts/benchmark-suite.sh
./scripts/soak-http.sh
```

The qualification matrix in `deploy/qualification-v1/matrix.json` distinguishes repository-verified, scheduled, and target-required gates. A local pass must never be rewritten as an AWS, Ceph, RDS, Valkey, IdP, ingress, or zone result.

## Deliberate non-goals

Graviton remains the bytes, identity, integrity, and storage layer. Document identity, document versions, business metadata, search, extraction, embeddings, workflows, and case-management state belong in downstream systems. Tika, a schema registry, a search engine, and a general document model are not current Graviton modules.
