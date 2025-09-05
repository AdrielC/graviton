# Graviton Roadmap (Aligned with CAS Spec)

This roadmap reflects priorities for the CAS layer and related drivers/tooling. Time estimates are rough and assume 2–4 engineers part-time on the area. Owners are indicative and can be adjusted.

## Milestone 1: P0 Foundational CAS (4–6 weeks)
- Owner: Core Team
- Deliverables:
  - `storeBlock(attrs): ZSink[...]`, `readBlock`, `readBlob`, `statBlob`
  - Opaque types (`Offset`, `Size`, `BlockKey`, `BlobKey`, `StoreId`, `Sector`)
  - Manifest helpers and validation
  - Hashing: BLAKE3 default; FIPS mode (SHA-256)
  - Drivers: In-memory and POSIX (test/demo-ready)
  - Docs: CAS layer spec, ingest and read guides
- Exit criteria:
  - Deterministic ingest→read→verify across local drivers
  - Property tests for block splitting and manifest assembly

## Milestone 2: Placement & Replication (P1, 3–5 weeks)
- Owner: Core Team, with Ops consult
- Deliverables:
  - Entities: `Store`, `Sector`, `Replica`, `ReplicaStatus`
  - APIs: `registerStore`, `listReplicas`, `addReplica`, `probeReplica`
  - Replication policy (min active replicas), store lifecycle (drain/disable)
  - Periodic health probes and placement strategy (weighted / round-robin)
- Exit criteria:
  - Blobs readable under single-store outage
  - Health probe metrics published

## Milestone 3: Observability (P1, 2–3 weeks)
- Owner: Platform Team
- Deliverables:
  - Structured logs with correlation IDs
  - OpenTelemetry tracing around ingest/read/placement
  - Prometheus metrics: availability, latency, error rate; per-store histograms
- Exit criteria:
  - Dashboards for ingest latency and read error rate

## Milestone 4: Repair and GC (P2, 3–4 weeks)
- Owner: Core Team
- Deliverables:
  - Repair worker for `Lost/Quarantined` replicas
  - GC of `Deprecated` replicas (with dry-run)
  - Consistency scanner and orphan detection
- Exit criteria:
  - Policy-compliant replication restored after simulated loss

## Milestone 5: Performance (P2, 2–3 weeks)
- Owner: Core Team
- Deliverables:
  - Tunable `MaxBlockSize` with benchmarks
  - Hashing/upload pipelining and batching
  - Read-ahead and prefetch for sequential reads
- Exit criteria:
  - Target throughput achieved on POSIX and S3 (documented)

## Milestone 6: Driver Expansion (P2, 4–6 weeks)
- Owner: Drivers Team
- Deliverables:
  - S3 driver (MVP → robust): multipart, range reads, SSE, retries
  - GCS driver with parity behaviors
  - Azure Blob driver with tiering support
  - Ceph/RADOS driver (alpha)
- Exit criteria:
  - Cross-driver conformance and performance parity matrix

## Milestone 7: Security & FIPS (P1, 2 weeks)
- Owner: Security + Core
- Deliverables:
  - FIPS hash mode gating; deterministic integrity verification
  - Credentials handling for stores; redaction in logs
- Exit criteria:
  - FIPS mode passes parity tests with refs

## Milestone 8: Read Path Polish (P1, 2–3 weeks)
- Owner: Core Team
- Deliverables:
  - Range reads and random access helpers
  - Verify-on-read policies (none/spot/full) with sampling knobs
  - Read fallback chain across replicas
- Exit criteria:
  - Large-blob random access validated in tests

## Milestone 9: Repair/GC Tooling & CLI (P2, 2–3 weeks)
- Owner: Platform Team
- Deliverables:
  - CLI for `statBlob`, integrity verify, repair
  - Admin tooling for store draining and replica inspection
- Exit criteria:
  - Runbooks validated by on-call exercises

## Milestone 10: 1.0 Readiness (P1, 2 weeks)
- Owner: Core + Docs
- Deliverables:
  - ADRs finalized; API stability review
  - Backward compatibility tests for manifests and keys
  - Documentation sweep and examples
- Exit criteria:
  - Version 1.0 tag with changelog and migration notes

---

### Dependencies
- M2 depends on M1 (replication needs core CAS+manifest primitives)
- M4 depends on M2 (repair and GC need placement metadata)
- M6 can start after M1, but tuning awaits M5

### Risks & Mitigations
- Hashing bottlenecks → pipeline and tune block size
- Backend throttling → adaptive concurrency and retry policies
- Consistency drift → periodic scanners and integrity checks

### Tracking
- Each milestone maps to GitHub milestones; issues are tagged by area: `core`, `drivers`, `ops`, `docs`.