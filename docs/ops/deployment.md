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

java -jar graviton-server.jar
```

With no `GRAVITON_S3_ENDPOINT`, the AWS SDK default credential provider chain is used. For MinIO, set its endpoint and access credentials as described in [Configuration Reference](../guide/configuration-reference.md). Every process sharing the PostgreSQL manifest database and block repository must use the same maintenance namespace.

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
| `GET /api/health/ready` | Active block and manifest backends respond within five seconds; a Shardcake node must also have an assigned shard |
| `GET /api/health` | Compatibility alias for liveness |
| `GET /api/stats` | JSON process counters |
| `GET /metrics` | Prometheus text including HTTP request, error, and latency observations |

Stats and metrics require `observability.read` when security is enabled. Readiness is necessary for traffic admission but is not a substitute for an end-to-end canary or restore drill.

## Upgrade sequence

1. Back up manifests and blocks together, or take coordinated snapshots.
2. Restore and verify the backup in isolation.
3. Read the compatibility and migration notes for the target version.
4. Run `scripts/migrate-postgres.sh` once for shared deployments.
5. Deploy by immutable image digest.
6. Wait for readiness, upload a canary, retrieve it, and run server verification.
7. Retain the prior artifact and data snapshot until the acceptance window closes.

For filesystem mode, stop the prior writer before starting the new one. For S3 plus PostgreSQL, do not use rolling replicas until that exact version pair has passed concurrent-process and rollback tests.

## Backup, GC, and performance

- [Backup, Restore, and Garbage Collection](./backup-restore.md)
- [Performance Measurement](./performance.md)
- [Production Readiness](./production-readiness.md)
