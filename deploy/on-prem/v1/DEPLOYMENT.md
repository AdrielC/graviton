# Quasar v1 (On-Prem) — Docker Compose Bundle

This folder is a **“do it for real”** starting point for an on‑prem Quasar v1 deployment:

- **Gateway**: Caddy (optional profile)
- **Quasar API**: `quasar-http` (minimal runnable scaffolding)
- **Job Runner**: `quasar-legacy` (minimal runnable scaffolding)
- **Postgres**: system-of-record, bootstrapped from the authoritative DDL in `modules/pg/ddl.sql`
- **Redis**: sessions / rate limits / upload state (service present; integration is scaffold)
- **MinIO**: object store backing (service + buckets present; Graviton adapter is still scaffold)
- **Graviton node**: `graviton-server` (demo HTTP surface + metrics + datalake stream)

## What “v1” includes (scope lock)

**Must include**
- Quasar API (documents, versions, metadata patching, jobs API surface)
- Postgres durable state
- Redis for volatile state
- Graviton nodes (CAS)
- Shard routing (Shardcake or equivalent) between Quasar → Graviton
- MinIO backing for Graviton
- Job Runner (outbox poller + plugin executor)
- Observability baseline (metrics + logs)
- One happy-path workflow (OCR) to prove job wiring

**Explicitly not in v1**
- Cedar SOAP compatibility
- multi-tenant SaaS control plane
- fancy policy engines
- cross-cluster replication

## Topologies

### Layout A — Single host (pilot/dev)
Run all services on one box (this compose file).

### Layout B — Three host (minimum “real” on-prem)
Split compose stacks by function:
- **Host 1**: Quasar API + Redis + Job Runner
- **Host 2**: Postgres
- **Host 3**: MinIO + Graviton nodes

Keep the config contract identical: only DNS/ips change.

## Config contract (ops touchpoint)

Mount **one folder**:

`/etc/quasar/config/`
- `quasar.conf`
- `db.conf`
- `redis.conf`
- `storage.conf`
- `minio.conf`
- `plugins.conf`
- `logging.conf`
- `secrets/` (mounted, not baked)

In Compose, this is the local `./config/` and `./secrets/` directories.

## Quick start (Docker Compose)

From this directory:

```bash
cp .env.example .env
docker compose up --build
```

### URLs
- **Quasar API**: `http://localhost:8080/v1/health`
- **Quasar readiness (deps)**: `http://localhost:8080/v1/ready`
- **Graviton demo API**: `http://localhost:8081/api/health`
- **Graviton metrics**: `http://localhost:8081/metrics`
- **MinIO console**: `http://localhost:9001`

### Database bootstrap

On first boot, Postgres runs:
- `migrations/00_roles.sql` (creates roles)
- `modules/pg/ddl.sql` (authoritative schema for `core`, `graviton`, `quasar`)
- `migrations/20_grants.sql` (ownership + grants)

## Observability baseline (v1 minimum)

**Metrics**
- Graviton: `/metrics` (Prometheus text format)
- Quasar: `/v1/ready` includes a dependency snapshot (DB/Redis/MinIO)

**Logs**
- All services log to stdout/stderr (Compose-friendly).
- Treat request IDs/correlation IDs as a v1 requirement (plumb end-to-end).

## Smoke test plan (before anyone points a court at it)

### A. Upload/download parity
Target behavior:
1. Create upload session
2. Stream 100MB upload
3. Finalize → version created
4. Download → hash matches original

**Status in this repo today**: API scaffolding exists; full streaming upload/download path is not implemented yet.

### B. Metadata patching
Target behavior:
1. Write canonical core metadata
2. Apply JSON Patch update
3. Confirm validation + audit + versioning semantics

**Status**: design docs exist; service implementation is still landing.

### C. Workflow execution (OCR)
Target behavior:
1. Enqueue OCR job
2. Job runner leases + executes plugin
3. Derived metadata written with provenance
4. Deterministic view keys

**Status**: outbox schema exists (`quasar.outbox_job`); plugin runner is scaffold.

### D. AuthZ (deny-first)
Target behavior:
1. Token allowed reads
2. Token denied reads
3. Audit contains both

**Status**: not wired yet (v1 requirement).

## Failure drills (document the observed behavior)

- Kill Quasar mid-upload → session recovers or fails cleanly
- Kill one Graviton node → routing reroutes (if replication exists) or fails loudly
- Kill MinIO → read-only or clear failures
- Restart DB → reconnect behavior
- Crash job runner mid-plugin → lease + retry semantics

If you can’t explain failure behavior, courts won’t trust it.

## Next obvious Kubernetes path (shape, not manifests)

Compose maps cleanly to K8s:
- each service → Deployment/StatefulSet
- volumes → PVCs (separate classes for DB vs object store vs scratch)
- config → ConfigMap + Secret
- gateway → Ingress
- readiness checks → HTTP probes

Keep the config contract (`/etc/quasar/config`) identical across Compose and K8s.

