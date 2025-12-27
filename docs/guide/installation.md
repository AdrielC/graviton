# Installation

This guide covers installing and configuring Graviton for development and production use.

## System Requirements

### Minimum Requirements

- **Java**: OpenJDK 21 or higher
- **Memory**: 2GB RAM (4GB+ recommended)
- **Storage**: Depends on your data volume
- **OS**: Linux, macOS, or Windows with WSL2

### Optional Components

- **PostgreSQL 18+**: Required by the current server for manifest storage and metadata.
- **S3-compatible storage (MinIO/AWS)**: Optional; used for block storage when `GRAVITON_BLOB_BACKEND=s3|minio`.
- **Docker**: Optional; useful for running PostgreSQL/MinIO locally.

## Installation Methods

### From Source

The recommended way to get started:

```bash
# Clone the repository
git clone https://github.com/AdrielC/graviton.git
cd graviton

# Compile everything
./sbt compile

# Run formatter and tests
TESTCONTAINERS=0 ./sbt scalafmtAll test

# Optional: rebuild the Scala.js dashboard for the /demo docs page
./sbt buildFrontend
```

### Using SBT Dependency

Add Graviton modules to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "io.graviton" %% "graviton-core" % "0.1.0-SNAPSHOT",
  "io.graviton" %% "graviton-runtime" % "0.1.0-SNAPSHOT",
  "io.graviton" %% "graviton-streams" % "0.1.0-SNAPSHOT"
)
```

::: warning
Graviton is currently in development. Releases will be published to Maven Central once v0.1.0 is stable.
:::

## Backend Configuration

### PostgreSQL Backend

Graviton currently expects a PostgreSQL database for manifest storage (even when blocks live on S3/MinIO or the filesystem).

1. **Install PostgreSQL**:

```bash
# Ubuntu/Debian
sudo apt-get install postgresql-18

# macOS
brew install postgresql@18

# Or use Docker
docker run -d \
  --name graviton-postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=graviton \
  -p 5432:5432 \
  postgres:18
```

2. **Wait for Postgres to accept connections** (Docker case):

```bash
until psql -h localhost -U postgres -d graviton -c "select 1" >/dev/null 2>&1; do
  sleep 1
done
```

2. **Apply DDL Schema**:

```bash
# Canonical, deployable schema (also used by dbcodegen)
psql -U postgres -d graviton -f modules/pg/ddl.sql
```

3. **Configure connection (env vars used by the server)**:

```bash
export PG_JDBC_URL="jdbc:postgresql://localhost:5432/graviton"
export PG_USERNAME="postgres"
export PG_PASSWORD="postgres"
```

4. **Regenerate bindings** (only if you modify the schema):

```bash
PG_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/graviton \
PG_USERNAME=postgres \
PG_PASSWORD=postgres \
./sbt "dbcodegen/run"
```

### S3/MinIO backend (block storage)

The current server’s `s3|minio` backend uses the MinIO-compatible env contract (see `S3BlockStore.layerFromEnv`).
Set:

- `QUASAR_MINIO_URL` (for example `http://localhost:9000`)
- `MINIO_ROOT_USER`
- `MINIO_ROOT_PASSWORD`
- Optional: `GRAVITON_S3_BLOCK_BUCKET` (default: `graviton-blocks`)
- Optional: `GRAVITON_S3_BLOCK_PREFIX` (default: `cas/blocks`)
- Optional: `GRAVITON_S3_REGION` (default: `us-east-1`)

```bash
export QUASAR_MINIO_URL="http://localhost:9000"
export MINIO_ROOT_USER="minioadmin"
export MINIO_ROOT_PASSWORD="minioadmin"
export GRAVITON_S3_BLOCK_BUCKET="graviton-blocks"
export GRAVITON_S3_BLOCK_PREFIX="cas/blocks"
export GRAVITON_S3_REGION="us-east-1"
```

You must also ensure the bucket exists before your first upload (MinIO example):

```bash
mc alias set local "$QUASAR_MINIO_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb local/"$GRAVITON_S3_BLOCK_BUCKET"
```

### Filesystem backend (block storage)

If you want blocks on local disk instead of S3/MinIO, set:

```bash
export GRAVITON_BLOB_BACKEND="fs"
export GRAVITON_FS_ROOT="./.graviton"
export GRAVITON_FS_BLOCK_PREFIX="cas/blocks"
```

### RocksDB Backend

The repository contains a RocksDB adapter module, but the current server wiring does not use it yet. Treat it as experimental.

## Running the Server

### Development Mode

```bash
./sbt "server/run"
```

The server will start with:
- HTTP on port `8081` by default (override with `GRAVITON_HTTP_PORT`)
- Health at `GET /api/health`
- Metrics at `GET /metrics`

### Verify the server is alive

```bash
curl -fsS "http://localhost:8081/api/health" | jq .
```

### First upload via HTTP

```bash
curl -fsS \
  -H "Content-Type: application/octet-stream" \
  -X POST --data-binary @/path/to/file \
  "http://localhost:8081/api/blobs" \
  | jq -r .
```

### Production Build

The repository currently focuses on **developer-first** execution via SBT (`./sbt "server/run"`). If you need a production-style artifact today, prefer the provided container wiring under `deploy/` (and treat it as the reference for how env vars are expected to be supplied).

### Docker Deployment

```dockerfile
FROM eclipse-temurin:21-jre-alpine

COPY target/graviton-server-assembly.jar /app/graviton.jar

EXPOSE 8080 50051

CMD ["java", "-jar", "/app/graviton.jar"]
```

## Configuration model (current server)

The current `graviton-server` reads configuration from **environment variables** (see `graviton.server.Main`). A HOCON `application.conf` layout is **not** wired up yet, so treat any HOCON examples you find as forward-looking design notes rather than runnable configuration.

## Verification

Test your installation:

```bash
# Check if the server is running
curl http://localhost:8081/api/health

# View metrics
curl http://localhost:8081/metrics

# Run tests (without TestContainers)
TESTCONTAINERS=0 ./sbt test
```

## Troubleshooting

### PostgreSQL Connection Issues

```bash
# Check if PostgreSQL is running
sudo systemctl status postgresql

# Test connection
psql -U postgres -d graviton -c "SELECT 1"
```

### “relation … does not exist” / missing tables

Apply the schema:

```bash
psql -U postgres -d graviton -f modules/pg/ddl.sql
```

### MinIO bucket errors

If you use `GRAVITON_BLOB_BACKEND=s3|minio`, ensure the bucket exists:

```bash
mc alias set local "$QUASAR_MINIO_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc ls local
mc mb local/"$GRAVITON_S3_BLOCK_BUCKET"
```

### Port Conflicts

If port 8081 is in use:

```bash
# Find processes using the ports
lsof -i :8081

# Kill or reconfigure
```

### Memory Issues

Increase JVM heap size:

```bash
export JAVA_OPTS="-Xmx4g -Xms2g"
```

## Next Steps

- **[Configuration Guide](../ops/deployment.md)** — Advanced configuration options
- **[Architecture Overview](../architecture.md)** — Understand the system design
- **[First Upload](./getting-started.md#your-first-upload)** — Try uploading data

::: tip
If you don’t have PostgreSQL installed locally, run it via Docker and keep `PG_JDBC_URL/PG_USERNAME/PG_PASSWORD` pointing at the container.
:::
