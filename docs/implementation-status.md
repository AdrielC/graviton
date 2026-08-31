# Implementation Status

This page separates released artifacts, optional runtime modes, and deployment-specific qualification. It was audited against the `v0.8.0` release commit, `12aa27be`, on 2026-08-31.

## Version boundary

The latest published release is [`v0.8.0`](https://github.com/AdrielC/graviton/releases/tag/v0.8.0). Its release workflow completed successfully, and its 16 library modules are available from Maven Central. The server, CLI, and browser applications are runnable deliverables, not Maven library artifacts.

Status terms used below:

- **Released in v0.8.0** means the implementation is present in the signed Maven Central release or its corresponding runnable release assets.
- **Optional in v0.8.0** means the implementation is released but mounted only when its configuration is enabled.
- **Target qualification required** means repository proof exists, but a provider, topology, capacity, or failure claim must be established in the operator's environment.
- **Not shipped** means there is no supported module or runtime path.

## Capability ledger

| Surface | Availability | Executable boundary |
| --- | --- | --- |
| Embedded and filesystem CAS | Released in v0.8.0 | `CasBlobStore`, `FsBlockStore`, and `FsBlobManifestRepo` have lifecycle, restart, integrity, backup, restore, and GC tests. Controlled use still needs operator capacity, access, and backup policy. |
| S3-compatible blocks plus PostgreSQL manifests | Released in v0.8.0 | CI runs real MinIO and PostgreSQL integration. That does not qualify every S3, Ceph RGW, RDS, network, or failure mode. |
| HTTP v1 and JVM streaming SDK | Released in v0.8.0 | Socket and packaged-process suites prove real streaming lifecycles. The 1 TiB test is a lazy logical-size contract, not a physical 1 TiB transfer. |
| Streaming gRPC | Released in v0.8.0 | Real-socket tests cover live streaming, authenticated distributed request quota, and delivered-egress charging immediately before frame emission. Target Redis or Valkey behavior remains qualification work. |
| PDF-aware ingest | Released in v0.8.0 | `PdfAwareChunkerSpec` proves signature validation and structural chunk boundaries. Page extraction, semantic chunking, malware screening, and arbitrary font replacement are not server features. |
| Authentication and authorization | Released in v0.8.0 | RS256 OIDC/JWKS, capabilities, exact CORS origins, trusted proxies, and audit behavior are tested. Real issuer and ingress acceptance remain operator work. |
| Replication and fixed 2+1 erasure | Optional in v0.8.0 | Library and destructive local drills prove the declared topology. They cannot prove target failure domains are physically independent. |
| Shardcake upload locality | Optional in v0.8.0 | Shardcake assigns upload ownership and direct owner streaming. It does not authenticate callers, serialize payload bytes, or replace CAS correctness. |
| Backend law kit | Released in v0.8.0 | `graviton-backend-laws` publishes reusable streaming, crash, maintenance, cursor, and tenant laws. A third-party backend supports only the laws and failures it actually runs. |
| Pure transducer library | Released in v0.8.0 | Recommended aggregate APIs return explicit ZIO Blocks schema-backed summaries. The v0.7 Record-shaped method names remain deprecated binary-compatible shims. Collecting runners require bounded input. |
| Multi-tenant storage isolation | Optional in v0.8.0 | Tests cover authenticated isolation, private manifests, PostgreSQL RLS, retained quota, and explicit shared trust domains. Shared deduplication exposes a content-membership signal. |
| Distributed transfer and traffic quotas | Optional in v0.8.0 | Redis or Valkey scripts atomically limit active transfers, requests, and delivered download bytes across authenticated HTTP and gRPC. Target failover and partition behavior remains unqualified. |
| Versioned PostgreSQL byte-substrate schema | Released in v0.8.0 | CI applies immutable `V001__graviton.sql` twice to a clean PostgreSQL service and checks the 25-table Graviton-only boundary. There is no document schema or legacy migration. |
| Manifest metadata, authentication, snapshots, and cold-block scrub | Optional in v0.8.0 | GVM4, PostgreSQL, integrity, snapshot, repair, and maintenance suites are executable. This clean pre-1.0 line has no legacy manifest reader or backfill path. |
| Operator control plane and local Datastar console | Optional in v0.8.0 | Typed snapshots, bounded SSE, live metrics, and server-rendered routes are tested. Snapshots are process-local; Prometheus is the historical surface. |
| Production Qualification and Telemetry v1 | Released in v0.8.0 | Portable SLOs, 15 recording rules, 16 alerts, a 15-panel Grafana dashboard, operator CLI, and a 16-gate matrix ship. Five gates remain `target-required`. |
| RocksDB | Released in v0.8.0 as typed KV only | Reopen and persistence tests pass. It is not a `BlockStore`, `BlobManifestRepo`, or complete CAS backend. |
| Apache Tika | Not shipped | No `graviton-tika` project, route, configuration, or metric exists. Extraction and indexing are outside the byte substrate. |

The machine-readable evidence map is [`docs/status/implementation-evidence.json`](https://github.com/AdrielC/graviton/blob/main/docs/status/implementation-evidence.json). CI validates that every listed source, test, documentation file, and required symbol still exists.

## What production qualification still means

Repository tests establish behavior on their exact fixtures and runners. They do not establish a universal customer count, throughput envelope, recovery objective, provider service level, or security posture. Before customer traffic, run the target-only gates against the exact image digest, region, account, instance sizes, IdP, ingress, PostgreSQL or RDS mode, S3 or Ceph service, and Redis or Valkey topology.
