# Design Documents

Design pages explain implemented internals or clearly labeled proposals. They are not capability announcements. For the released-versus-main boundary, use the [implementation status ledger](../implementation-status.md).

## Implemented designs

| Area | Current implementation | Detail |
| --- | --- | --- |
| Byte and product boundary | Graviton owns streamed bytes, content identity, integrity, and storage, not document semantics | [Scope and product boundary](../scope.md) |
| Content identity and schemas | Refined content keys, bounded wire records, GVM4 metadata on current `main`, and explicit codec limits | [Schema and types](../core/schema.md), [Manifests and frames](../manifests-and-frames.md) |
| Stream processing | ZIO Stream production path plus the separate pure Scan and Transducer libraries | [Scans and events](../core/scans.md), [Transducer algebra](../core/transducers.md) |
| Chunking | Fixed, delimiter, FastCDC, BuzHash, Rabin, and PDF-aware structural chunkers with hard block ceilings | [Chunking strategies](../ingest/chunking.md) |
| Storage | Filesystem CAS, S3-compatible blocks, PostgreSQL manifests and object primitives, plus RocksDB typed KV | [Runtime backends](../runtime/backends.md) |
| Durability | Rendezvous replication, fixed 2+1 erasure, durable repair cursors, and scheduled convergence | [Failure-domain durability](../runtime/replication.md) |
| Security and tenancy | OIDC/JWKS, capabilities, audit, default-isolated tenants, explicit shared trust domains, and quotas | [Multi-tenant storage](../runtime/multi-tenancy.md), [Secure API quickstart](../guide/secure-api-quickstart.md) |
| Operations | Typed health snapshots, Prometheus metrics, SLO rules, alerts, Grafana, qualification contracts, backup, and restore tooling | [Production readiness](../ops/production-readiness.md), [Operator control plane](../ops/control-plane.md) |
| Browser comparison | Local streamed file analysis, exact block overlap, and bounded PDF edit comparison | [CAS Playground design contract](./cas-playground.md) |

## Accepted boundaries, not implementations

- Compression and encryption are not executable CAS transform plans. They require paired streaming read and write paths, identity semantics, key-provider boundaries, and compatibility vectors.
- Tiered storage, geo-replication, query, search, extraction, embeddings, and document workflows are not current Graviton features.
- Apache Tika is not a module. See [the explicit Tika boundary](../modules/tika.md).
- The ZIO Blocks register document is a performance proposal for an experimental pure pipeline. It is not the production upload orchestrator. See [the register plan](./zio-blocks-register-plan.md).

## Design acceptance rule

A design moves to implemented only when the public or internal service exists, its resource and error boundaries are explicit, executable tests cover success and failure, and operational documentation states the remaining deployment limits. The machine-readable claim map in `docs/status/implementation-evidence.json` keeps major public claims attached to source and test symbols.
