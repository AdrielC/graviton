# Deployment

Graviton ships a runnable fat JAR, a non-root distroless container definition, and a conservative Kubernetes example. Read [Production Readiness](./production-readiness.md) before selecting a topology.

## Build and prove the artifact

```bash
./sbt server/assembly
./scripts/smoke-packaged-server.sh
```

The smoke script starts the assembled JAR twice. The open server proves upload, byte equality, range reads, ETag preconditions, and verification. The authenticated server proves valid-token operation, anonymous rejection, and capability denial.

## Filesystem service

```bash
export GRAVITON_HTTP_PORT=8081
export GRAVITON_GRPC_PORT=9090
export GRAVITON_BLOB_BACKEND=fs
export GRAVITON_FS_ROOT=/var/lib/graviton
export GRAVITON_FS_BLOCK_PREFIX=cas/blocks

java -jar modules/server/graviton-server/target/scala-3.8.4/graviton-server-*.jar
```

The built-in server, CLI, and `Graviton.fs` facade coordinate complete blob operations and exclusive maintenance through `<root>/cas/.maintenance.lock`. Every process must use the same root, and the underlying filesystem must provide working cross-client file-lock semantics. Raw `CasBlobStore` construction bypasses that protection. Keep `Recreate` as the default upgrade policy until the target shared volume has passed overlapping-process and rollback tests.

## Container

Build the JAR first, then the image:

```bash
./sbt server/assembly
docker build -t graviton:local .
docker run --rm \
  -p 8080:8080 \
  -p 9090:9090 \
  -v graviton-data:/data \
  graviton:local
```

The image runs as uid/gid `65532`, has a read-only application directory, stores filesystem data under `/data`, caps the JVM heap by container memory, and exits on out-of-memory errors.

Release tags publish multi-architecture images to `ghcr.io/adrielc/graviton`. Do not use a release tag until its workflow and attestation are green.

## Hardened two-node Compose

`deploy/production` is the operator bundle for a two-node Shardcake topology
with PostgreSQL manifests and MinIO blocks. It requires a real OIDC boundary,
resolves the release tag to an immutable image digest, generates independent
database, object-store, and internode secrets, validates the complete typed
configuration in the release image, and binds public ports to loopback by
default.

```bash
export GRAVITON_OIDC_ISSUER=https://identity.example.com/
export GRAVITON_OIDC_AUDIENCE=graviton
export GRAVITON_OIDC_JWKS_URI=https://identity.example.com/.well-known/jwks.json

./deploy/production/operator.sh init
./deploy/production/operator.sh up
```

The application containers run non-root with a read-only root filesystem,
all Linux capabilities dropped, `no-new-privileges`, bounded temporary space,
and a Java health probe included in the same signed artifact. PostgreSQL and
MinIO are pinned by digest, stay on the private Compose network, and persist to
named volumes. Put a TLS ingress in front of the loopback listeners and have it
overwrite forwarding headers before changing the bind address.

Useful commands:

```bash
./deploy/production/operator.sh validate
./deploy/production/operator.sh status
./deploy/production/operator.sh logs
./deploy/production/operator.sh backup
```

The generated `.env` is mode `0600`, ignored by Git, and never printed by the
validator. The checked-in example contains placeholders only.

## Kubernetes

`deploy/kubernetes/graviton.yaml` is intentionally a one-replica `ReadWriteOnce` filesystem deployment with `Recreate` rollout. It includes:

- non-root uid/gid and `RuntimeDefault` seccomp
- dropped Linux capabilities and no privilege escalation
- read-only root filesystem with explicit `/data` and `/tmp` mounts
- liveness and backend-aware readiness probes
- CPU and memory requests and limits
- no service-account token
- same-namespace ingress only by default
- OIDC values from a Kubernetes Secret

Review the storage class, capacity, ingress source, egress policy, resources, image digest, and secret delivery before applying it. The example tag is replaced by the release workflow's published image; pin the resulting digest in a controlled environment.

## AWS Production Cell v1

`deploy/aws-cell-v1` is the empty-store AWS deployment contract. It provisions private three-zone ECS/Fargate compute with stable Shardcake identities, a singleton manager, TLS ALB ingress, Multi-AZ RDS PostgreSQL, separate KMS-encrypted S3 block and staging buckets, an encrypted Multi-AZ Valkey admission coordinator, Cloud Map discovery, IAM task roles, and a one-shot database bootstrap gate. Long-running services remain absent until secrets, Valkey AUTH, schema, runtime-role privileges, and the initial isolated tenant have been established.

```bash
./deploy/aws-cell-v1/operator.sh validate
./deploy/aws-cell-v1/operator.sh plan
```

The validation command is local and non-mutating. The plan requires a reviewed remote Terraform backend and non-secret variable file. `operator.sh up` creates billable AWS resources, so run it only after reviewing the plan and estimated charges. The bundle writes generated credentials directly to Secrets Manager rather than Terraform state, then installs Valkey AUTH through a fail-closed operator step before any long-running node can exist. See [AWS Production Cell v1](https://github.com/AdrielC/graviton/tree/main/deploy/aws-cell-v1) for bootstrap, qualification, fault, restore, and capacity gates.

## Shared S3 plus PostgreSQL

Apply the migration before startup:

```bash
export GRAVITON_DATABASE_URL=postgresql://user@postgres/graviton
export PGPASSWORD='from-a-secret-source'
./scripts/migrate-postgres.sh
```

Then configure the server:

```bash
export GRAVITON_BLOB_BACKEND=s3
export PG_JDBC_URL=jdbc:postgresql://postgres:5432/graviton
export PG_USERNAME=graviton
export PG_PASSWORD='use-a-secret-source'
export GRAVITON_S3_BLOCK_BUCKET=graviton-blocks
export GRAVITON_S3_BLOCK_PREFIX=cas/blocks
export GRAVITON_S3_REGION=us-east-1
export GRAVITON_MAINTENANCE_NAMESPACE=production-cas
export GRAVITON_MANIFEST_INTEGRITY_REQUIRED=true
export GRAVITON_MANIFEST_INTEGRITY_KEY_ID=manifest-v2
export GRAVITON_MANIFEST_INTEGRITY_HMAC_KEY_BASE64="$MANIFEST_HMAC_KEY_FROM_SECRET_MANAGER"
export GRAVITON_MANIFEST_INTEGRITY_PREVIOUS_KEYS_BASE64="$RETIRED_MANIFEST_KEYS_FROM_SECRET_MANAGER"

java -jar graviton-server.jar
```

Set the provider's explicit S3 endpoint and access credentials as described in [Configuration Reference](../guide/configuration-reference.md). Every process sharing the PostgreSQL manifest database and block repository must use the same maintenance namespace.

Required manifest authentication rejects missing, reordered, or modified manifest metadata before fetching block payloads. The optional previous-key value is a comma-separated `key-id:base64` ring for rotations. Keep every key in the deployment secret manager. Do not place keys in image layers, Compose files, command arguments, benchmark output, or logs.

For Graviton-managed block replication, create each bucket first and declare its real failure domain:

```bash
export GRAVITON_REPLICATION_TARGETS='zone-a|az-a|graviton-blocks-a,zone-b|az-b|graviton-blocks-b,zone-c|az-c|graviton-blocks-c'
export GRAVITON_REPLICATION_DESIRED_REPLICAS=3
export GRAVITON_REPLICATION_WRITE_QUORUM=2
export GRAVITON_REPLICATION_REPAIR_INTERVAL=5m
```

The packaged server then uses stable rendezvous placement for each block and starts a supervised bounded repair scrub. Omitting the quorum requires every desired target, which is the safe default. A lower explicit quorum trades immediate durability for availability; failed targets remain observable repair work.

Resumable S3 uploads also require `GRAVITON_S3_TMP_BUCKET` and the current PostgreSQL schema. Staging parts use adaptive bounded multipart writes, but final commit still streams every staged byte through MIME validation, content-defined or fixed chunking, hashing, deduplication, and manifest-last publication.

### Three-domain erasure qualification

The repository includes a complete local topology with three independent object-service processes and volumes, PostgreSQL metadata, live Prometheus rules, and a provisioned Grafana dashboard:

```bash
./scripts/demo-three-domain.sh up
./scripts/qualify-three-domain.sh | jq .
```

The qualification stops the locally preferred target and performs a byte-exact read through the two remote targets. It then destroys the complete second target volume, recreates that endpoint empty, waits for the supervised manifest scrub to regenerate its shards, and performs another byte-exact read. This is destructive only to the dedicated `graviton-three-domain` Compose topology. It does not claim to reproduce Ceph daemon, network, or control-plane behavior.

Open the live surfaces after startup:

- Graviton console: `http://127.0.0.1:58181/console`
- Prometheus: `http://127.0.0.1:59090`
- Grafana: `http://127.0.0.1:59300/d/graviton-slo`

## Multi-node upload locality

Shardcake locality requires the shared S3 plus PostgreSQL composition above. Apply the current schema and give the manager and nodes the same placement database, shard count, and internal token.

Manager process:

```bash
export GRAVITON_SHARDCAKE_ENABLED=true
export GRAVITON_SHARDCAKE_INTERNAL_TOKEN="$SHARDCAKE_TOKEN_FROM_SECRET_MANAGER"
export GRAVITON_SHARDCAKE_NUMBER_OF_SHARDS=1024
export GRAVITON_SHARDCAKE_MANAGER_API_PORT=8080
export GRAVITON_SHARDCAKE_POSTGRES_JDBC_URL=jdbc:postgresql://postgres:5432/graviton
export GRAVITON_SHARDCAKE_POSTGRES_USERNAME=graviton
export GRAVITON_SHARDCAKE_POSTGRES_PASSWORD="$SHARDCAKE_DB_PASSWORD_FROM_SECRET_MANAGER"

java -cp graviton-server.jar graviton.integration.shardcake.ShardcakeManagerMain
```

The release server JAR contains both entry points. From a source checkout, the equivalent manager command is `./sbt "shardcakeIntegration/runMain graviton.integration.shardcake.ShardcakeManagerMain"`.

Each Graviton node also sets:

```bash
export GRAVITON_SHARDCAKE_ENABLED=true
export GRAVITON_SHARDCAKE_HOST=graviton-0.graviton-headless
export GRAVITON_SHARDCAKE_CONTROL_PORT=54321
export GRAVITON_SHARDCAKE_UPLOAD_PORT=54322
export GRAVITON_SHARDCAKE_MANAGER_URI=http://graviton-shard-manager:8080/api/graphql
export GRAVITON_SHARDCAKE_NUMBER_OF_SHARDS=1024
export GRAVITON_SHARDCAKE_SERVER_VERSION=release-version
export GRAVITON_SHARDCAKE_INTERNAL_TOKEN="$SHARDCAKE_TOKEN_FROM_SECRET_MANAGER"
export GRAVITON_SHARDCAKE_POSTGRES_JDBC_URL=jdbc:postgresql://postgres:5432/graviton
export GRAVITON_SHARDCAKE_POSTGRES_USERNAME=graviton
export GRAVITON_SHARDCAKE_POSTGRES_PASSWORD="$SHARDCAKE_DB_PASSWORD_FROM_SECRET_MANAGER"

java -jar graviton-server.jar
```

Give every pod a stable, peer-reachable host identity. Permit manager-to-node control traffic on `54321`, node-to-node owner streams on `54322`, and node-to-manager GraphQL on `8080`. Do not expose those listeners publicly. The same secret authenticates all three internal paths, and the public JWT organization is checked against the upload tenant before any body bytes are pulled.

Shardcake 2.8.1 opens its internode gRPC channels in plaintext, and the direct owner stream is HTTP. Put these listeners behind a private cluster network and a service mesh or equivalent mTLS tunnel when transport encryption is required.

Run exactly one manager. Its PostgreSQL session lease makes accidental overlap fail at startup. During a node drain, Shardcake moves assignments to surviving nodes. Existing non-replayable streams are allowed to succeed on their current connection or fail normally; subsequent requests resolve again. Clients may retry only when they can reopen or regenerate the source.

Before a rollout, prove session distribution, repeated-session stickiness, node drain, reassignment, upload/readback, and CAS verification against the exact target network and stores. The repository's two-node test is executable evidence of the protocol behavior, not a substitute for that environment acceptance.

## Production OIDC

```bash
export GRAVITON_SECURITY_ENABLED=true
export GRAVITON_SECURITY_OIDC_ISSUER=https://id.example.com/
export GRAVITON_SECURITY_OIDC_AUDIENCE=graviton
export GRAVITON_SECURITY_OIDC_JWKS_URI=https://id.example.com/.well-known/jwks.json
export GRAVITON_SECURITY_REQUIRE_TLS=true
export GRAVITON_SECURITY_TRUST_PROXY_HEADERS=true
export GRAVITON_SECURITY_CORS_ALLOWED_ORIGINS=https://console.example.com
export GRAVITON_SECURITY_AUDIT_BACKEND=jdbc
export GRAVITON_SECURITY_AUTHORIZATION_BACKEND=token
```

Do not set `GRAVITON_SECURITY_DEV_SHARED_SECRET` in production. Enable trusted proxy headers only when the ingress overwrites client-provided forwarding headers. Use `authorization-backend=jdbc` only after provisioning the ACL data expected by the security module.

## Health and observability

| Endpoint | Meaning |
| --- | --- |
| `GET /api/health/live` | Process is alive and returns the packaged build version |
| `GET /api/health/ready` | Active block, manifest, resumable-ledger, and staging targets respond within five seconds; a Shardcake node must also have an assigned shard |
| `GET /api/health` | Compatibility alias for liveness |
| `GET /api/ops/v1/snapshot` | Current typed storage, placement, transfer-capacity, durability, dependency, and traffic state |
| `GET /api/ops/v1/events` | Complete sequenced operator snapshots over a bounded server-sent event stream |
| `GET /api/stats` | JSON process counters |
| `GET /metrics` | Native ZIO Metrics Prometheus text including JVM, ingest, HTTP, locality, and Shardcake health observations |

Operator state, stats, and metrics require `observability.read` when security is enabled. Readiness is necessary for traffic admission but is not a substitute for an end-to-end canary or restore drill.

## Clean-store deployment and replacement sequence

1. Start from an empty pre-1.0 store and apply the current checksum-locked PostgreSQL schema.
2. Deploy one immutable image digest with required manifest authentication.
3. Wait for readiness, upload a canary, retrieve it, and run server verification.
4. Back up manifests and blocks together, or take coordinated snapshots.
5. Restore and verify the backup in isolation.
6. Replace the manager and nodes one at a time and retain byte-exact lifecycle evidence.

For filesystem mode, stop the prior writer before starting the new one. For S3 plus PostgreSQL, run the same-version replacement qualification for the exact candidate image:

```bash
./scripts/qualify-node-replacement.sh graviton:candidate
```

The harness proves manager replacement, independent node replacement, writes during replacement, and byte-exact cross-node reads on one clean `GVM5` store. This pre-1.0 line does not include legacy manifest readers or backfill machinery. A future compatibility promise requires a separately designed format policy and explicit evidence.

After the replacement gate, run a longer fault workload:

```bash
./scripts/qualify-long-failure.sh 900 4194304 > long-failure.json
```

That workload uses durable resumable offsets and byte-exact readback while repeatedly stopping both nodes independently, restarting the manager, and taking the object and manifest stores offline. It retries acknowledged parts with stable identities and fails if recovery exhausts its bound or any readback differs.

## Backup, GC, and performance

- [Backup, Restore, and Garbage Collection](./backup-restore.md)
- [Credential Rotation](./credential-rotation.md)
- [Performance Measurement](./performance.md)
- [Production Readiness](./production-readiness.md)
