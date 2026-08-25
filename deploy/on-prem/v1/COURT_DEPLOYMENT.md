# Graviton — Court-Pilot Deployment Runbook

This document gets a single-host pilot of Graviton running behind TLS,
authenticated, and producing a tamper-evident audit trail, in roughly
30 minutes. It is deliberately scoped to **one court, one machine, one
operator** — the multi-node, IdP-federated, SOC2-audited production
shape is tracked separately in
[`/root/.claude/plans/please-come-up-with-adaptive-hinton.md`](../../../../root/.claude/plans/please-come-up-with-adaptive-hinton.md)
and [`ROADMAP.md`](../../../ROADMAP.md).

> **Read this entire document before touching production data.** The
> § "What this pilot is **NOT**" section calls out the residual gaps
> that *require human decisions or additional work* before a real court
> case load crosses the wire.

## 1. Prerequisites

On the target host (Linux, 4+ vCPU, 16 GiB RAM, 200 GiB SSD recommended):

- Docker Engine ≥ 24 and Docker Compose v2.
- A DNS record `graviton.court.example.gov → <public IP>` reachable from
  court staff endpoints. (If running fully air-gapped, see § 4.3.)
- Port 443 reachable from the court network; port 80 open only to
  Let's Encrypt's HTTP-01 validator (Caddy redirects to HTTPS).
- `psql` and `jq` locally for the bootstrap script.
- Outbound access to `repo1.maven.org` and `docker.io` during the first
  `docker compose build`.

## 2. First-time setup

```bash
git clone https://github.com/AdrielC/graviton.git
cd graviton/deploy/on-prem/v1

cp .env.court.example .env
$EDITOR .env          # replace every CHANGE-ME-* value
```

Required edits in `.env`:

| Var | Why |
|-----|-----|
| `POSTGRES_SUPERPASS` | Bootstraps the DB on first start; rotate immediately via `ALTER USER postgres WITH PASSWORD ...` and store in your secrets manager. |
| `PG_PASSWORD` | The `quasar_app` role Graviton connects as. Keep in sync with `migrations/00_roles.sql`. |
| `MINIO_ROOT_PASSWORD` | MinIO admin. Not exposed to the API but protects the blob buckets if the admin console is reachable. |
| `GRAVITON_SECURITY_DEV_SHARED_SECRET` | Strong (32+ char) random string. Signs pilot JWTs. |
| `GRAVITON_PUBLIC_HOST`, `GRAVITON_ACME_EMAIL` | Drive Caddy's Let's Encrypt provisioning. |
| `COURT_ADMIN_ORG_ID`, `COURT_ADMIN_PRINCIPAL_ID` | UUIDs the bootstrap script seeds into `quasar.org` / `quasar.principal`. Pick any UUIDv4s. |

## 3. Bring the stack up

```bash
# Build + start everything. First run pulls images + compiles Graviton.
docker compose --profile gateway up -d --build

# Wait ~60 s for Postgres to finish running the three init SQL files:
#   10_schema.sql  (modules/pg/ddl.sql: full domain + RLS policies)
#   20_grants.sql  (quasar_app grants + NOBYPASSRLS)
#   30_audit.sql   (append-only quasar.audit_log + verify helper)

docker compose ps           # every service should be "healthy" / running

# Seed the first org + admin + mint the pilot JWT + round-trip a canary.
./bootstrap-court.sh
```

The script exits non-zero if any step fails. On success it writes
`secrets/admin-token.json` (mode 0600) with a 24-hour JWT and prints
a smoke-test recipe.

## 4. Operating the pilot

### 4.1 Staff onboarding

For each court staff member, the IT admin:

1. Picks a fresh UUID for `principal_id`.
2. Inserts a `quasar.principal` row for that UUID under
   `COURT_ADMIN_ORG_ID`.
3. Creates ACL entries in `quasar.acl_entry` granting the right
   capability bits (see `graviton.security.Capability`).
4. Calls `POST /dev/token` with that `principal_id` to mint a JWT;
   delivers the token out-of-band.

A short-lived TTL (≤ 8 hours) is strongly recommended. Token rotation
is the operator's responsibility until OIDC is wired.

### 4.2 Monitoring

- **Liveness**: `curl https://$HOST/api/health` — unauthenticated.
- **Audit chain integrity**: once per day,
  ```sql
  SELECT * FROM quasar.verify_audit_chain_linkage('<org_id>');
  ```
  expect `ok = true` and a monotonically-increasing `total`. A `false`
  result is a page-the-operator event: something has truncated or
  re-ordered the audit table.
- **Prometheus**: Graviton exports its internal metrics on
  `/metrics` (behind auth when security is on).
- **Backup**: `postgres-data`, `minio-data`, and
  `secrets/admin-token.json` are the three volumes / paths that carry
  court data. Back them up nightly; snapshot the Postgres WAL on a
  separate schedule.

### 4.3 Air-gapped networks

If the host has no Internet egress:

1. Set `GRAVITON_PUBLIC_HOST` to the internal DNS name.
2. Comment out the `email {...}` and `{$GRAVITON_PUBLIC_HOST}` auto-TLS
   block in `config/Caddyfile`; add `tls /etc/caddy/cert.pem
   /etc/caddy/key.pem` pointing at your own CA-issued certs.
3. Mirror the Docker images into your internal registry and override
   `POSTGRES_IMAGE`, `MINIO_IMAGE`, `REDIS_IMAGE`, `CADDY_IMAGE` in
   `.env`.

## 5. Pre-flight checklist (sign off before real data lands)

Every line is a **blocker** unless explicitly marked optional.

### 5.1 Infrastructure

- [ ] Host is in a court-owned network segment (not a public cloud
      unless the court's IT has signed off).
- [ ] TLS cert is trusted by every client machine (Let's Encrypt or the
      court's internal CA).
- [ ] Public port 443 is the only externally reachable port; 8081, 9090,
      5432, 9000, and 9001 are firewalled to the internal interface.
- [ ] Docker daemon restart policy: `--restart=unless-stopped` on each
      service (Compose default).
- [ ] Postgres WAL archiving configured to a separate durable target.

### 5.2 Identity / AuthN

- [ ] `GRAVITON_SECURITY_DEV_SHARED_SECRET` rotated from the value in
      `.env.court.example`, minimum 32 random chars, stored in the
      court's secrets manager (not on disk long-term).
- [ ] If OIDC is available: `GRAVITON_SECURITY_DEV_SHARED_SECRET` is
      **empty**, a live RS256 JwtVerifier is wired at assembly time,
      and the `/dev/token` handle is removed from the Caddyfile.
- [ ] Token TTL policy decided and documented (default 1 h; pilot
      script uses 24 h).
- [ ] Staff offboarding runbook: delete `quasar.principal` + revoke
      `quasar.acl_entry` rows; any minted tokens expire on their TTL.

### 5.3 Data protection

- [ ] `quasar_app` role verified `NOBYPASSRLS`
      (`SELECT rolbypassrls FROM pg_roles WHERE rolname='quasar_app'` →
      `false`).
- [ ] Cross-tenant isolation test:
      ```sql
      SET LOCAL app.org_id = '<wrong org uuid>';
      SELECT count(*) FROM quasar.document;   -- must return 0
      ```
- [ ] MinIO bucket `graviton-blobs` is not publicly readable
      (`mc policy` shows `none`).
- [ ] Server-side encryption: MinIO `MINIO_KMS_AUTO_ENCRYPTION=on` or an
      external KMS wired. (Not automated by this bundle yet; operator
      sets this before first upload.)
- [ ] Backup strategy documented: RPO / RTO targets, destination,
      restore test performed at least once before go-live.

### 5.4 Audit

- [ ] Audit table partitioning verified (16 hash partitions created).
- [ ] First `verify_audit_chain_linkage` run returns `ok=true`.
- [ ] Nightly job scheduled to dump audit rows to WORM storage (S3
      Object Lock, immutable tape, etc.). **This is NOT automated by
      this bundle.** Sample dump:
      ```bash
      docker compose exec postgres \
        pg_dump -U quasar_migrate -d quasar \
          --table=quasar.audit_log --data-only \
          > "audit-$(date -Is).sql"
      ```
- [ ] SIEM integration decided: tail Docker logs to the court's log
      aggregator (access logs + audit rows).

### 5.5 Operational

- [ ] Operator has a working `docker compose down && docker compose up`
      runbook and has practiced a restore from backup on a throwaway VM.
- [ ] `bootstrap-court.sh` canary round-trip passes.
- [ ] On-call contact defined; paging rules include:
      - Graviton server 5xx rate > 0
      - `verify_audit_chain_linkage` returning `ok=false`
      - Disk usage on `/var/lib/docker/volumes/quasar-v1-onprem_postgres-data`
        > 80 %

## 6. What this pilot is **NOT**

- **Multi-tenant at scale.** The schema supports it (RLS is enforced)
  but operational multi-tenancy requires a user-management UI and
  IdP-backed role mapping that are not in this bundle.
- **High-availability.** Single Postgres, single MinIO, single Graviton
  node. Acceptable for a pilot, not for an active docket. Roadmap:
  Patroni / repmgr, MinIO distributed mode, multiple Graviton replicas.
- **Immutable audit WORM.** The hash chain detects tampering but the
  rows still live on mutable disk; commit to an external WORM target
  daily.
- **Legal-hold automation.** There is no UI / API for asserting or
  lifting a legal hold on a document; schema support exists
  (`quasar.policy`) but enforcement is a roadmap item.
- **FedRAMP / SOC2 / HIPAA certification.** The controls here are the
  building blocks for compliance evidence, not a certification in
  themselves.

## 7. Shutdown / decommission

```bash
docker compose down             # keeps volumes
docker compose down --volumes   # WIPES postgres-data + minio-data
```

Before decommission: export the audit log (§ 5.4), export the blob
buckets (`mc mirror local/graviton-blobs /backup/blobs`), and file
everything with the court records officer per your retention policy.

## 8. Support path

- Public tracker: https://github.com/AdrielC/graviton/issues
- For a real pilot, provisioning a direct escalation contact + an SLA
  is the court's decision; the codebase doesn't assume any vendor-paid
  support relationship.
