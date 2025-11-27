# Installation

This guide covers installing and configuring Graviton for development and production use.

## System Requirements

### Minimum Requirements

- **Java**: OpenJDK 21 or higher
- **Memory**: 2GB RAM (4GB+ recommended)
- **Storage**: Depends on your data volume
- **OS**: Linux, macOS, or Windows with WSL2

### Optional Components

- **PostgreSQL 16+**: For metadata and object storage backend
- **RocksDB**: Embedded key-value storage (automatically included)
- **S3-Compatible Storage**: AWS S3, MinIO, or compatible services
- **Docker**: For running PostgreSQL via TestContainers

## Installation Methods

### From Source

The recommended way to get started:

```bash
# Clone the repository
git clone https://github.com/AdrielC/graviton.git
cd graviton

# Compile everything
sbt compile

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

1. **Install PostgreSQL**:

```bash
# Ubuntu/Debian
sudo apt-get install postgresql-16

# macOS
brew install postgresql@16

# Or use Docker
docker run -d \
  --name graviton-postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=graviton \
  -p 5432:5432 \
  postgres:16
```

2. **Apply DDL Schema**:

```bash
# Find the DDL file in the backend module
psql -U postgres -d graviton -f modules/backend/graviton-pg/src/main/resources/ddl.sql
```

3. **Configure Connection**:

```bash
export PG_JDBC_URL="jdbc:postgresql://localhost:5432/graviton"
export PG_USERNAME="postgres"
export PG_PASSWORD="postgres"
```

4. **Regenerate Bindings** (if you modify the schema):

```bash
./sbt "dbcodegen/run"
```

### S3 Backend

Configure AWS credentials:

```bash
export AWS_ACCESS_KEY_ID="your-access-key"
export AWS_SECRET_ACCESS_KEY="your-secret-key"
export AWS_REGION="us-east-1"
export GRAVITON_S3_BUCKET="graviton-blobs"
```

For MinIO or S3-compatible storage:

```bash
export S3_ENDPOINT="http://localhost:9000"
export S3_PATH_STYLE_ACCESS="true"
```

### RocksDB Backend

RocksDB is embedded and requires no external setup. Configuration:

```bash
export ROCKSDB_PATH="/var/lib/graviton/rocksdb"
export ROCKSDB_CACHE_SIZE="256MB"
```

## Running the Server

### Development Mode

```bash
sbt "graviton-server/run"
```

The server will start with:
- gRPC on port `50051`
- HTTP on port `8080`
- Metrics on `http://localhost:8080/metrics`

### Production Build

Create an optimized assembly:

```bash
sbt "graviton-server/assembly"

# Run the fat JAR
java -jar modules/server/graviton-server/target/scala-3.*/graviton-server-assembly-*.jar
```

### Docker Deployment

```dockerfile
FROM eclipse-temurin:21-jre-alpine

COPY target/graviton-server-assembly.jar /app/graviton.jar

EXPOSE 8080 50051

CMD ["java", "-jar", "/app/graviton.jar"]
```

## Configuration Files

### application.conf

Create `application.conf` in your classpath:

```hocon
graviton {
  server {
    http {
      host = "0.0.0.0"
      port = 8080
    }
    
    grpc {
      host = "0.0.0.0"
      port = 50051
    }
  }
  
  storage {
    backend = "postgres" # or "s3", "rocksdb"
    
    postgres {
      url = ${PG_JDBC_URL}
      username = ${PG_USERNAME}
      password = ${PG_PASSWORD}
    }
    
    s3 {
      bucket = ${GRAVITON_S3_BUCKET}
      region = ${AWS_REGION}
    }
  }
  
  chunking {
    algorithm = "fastcdc"
    min-size = 256KB
    avg-size = 1MB
    max-size = 4MB
  }
}
```

## Verification

Test your installation:

```bash
# Check if the server is running
curl http://localhost:8080/health

# View metrics
curl http://localhost:8080/metrics

# Run integration tests
sbt "graviton-runtime/test"
```

## Troubleshooting

### PostgreSQL Connection Issues

```bash
# Check if PostgreSQL is running
sudo systemctl status postgresql

# Test connection
psql -U postgres -d graviton -c "SELECT 1"
```

### Port Conflicts

If ports 8080 or 50051 are in use:

```bash
# Find processes using the ports
lsof -i :8080
lsof -i :50051

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
For development, use `TESTCONTAINERS=1` to automatically spin up PostgreSQL in Docker.
:::
