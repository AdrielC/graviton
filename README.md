# Graviton

ZIO‑native content‑addressable storage inspired by Binny.

## Features

* Content‑addressable binary store with BLAKE3 hashing
* Pluggable blob stores (filesystem, S3/MinIO, …)
* ZIO Streams based APIs for non‑blocking I/O
* Media type detection utilities backed by Apache Tika

## Architecture

```mermaid
graph TD
    B[Block] -->|persists| S[BlobStore]
    B --> R[BlockStore]
    R --> F[File]
    F --> M[FileStore]
    M --> V[View]
```

```mermaid
sequenceDiagram
    participant C as Client
    participant BS as BlockStore
    participant BL as BlobStore
    participant RS as Resolver

    C->>BS: stream file bytes
    BS->>BL: write blocks
    BS->>RS: register sectors
    BS-->>C: FileKey
```

## Quickstart

### CLI

```bash
# ingest a file
graviton put README.md
# retrieve the file using the returned key
graviton get <fileKey> > README.copy.md
```

### HTTP Gateway

Assuming the gateway is running on `localhost:8080`:

```bash
# upload bytes
curl -X POST --data-binary @README.md http://localhost:8080/files
# download the stored file
curl http://localhost:8080/files/<fileKey> -o README.copy.md
```

Documentation lives under the [docs](docs/src/main/mdoc/index.md) directory and
is published as part of the project site.

## Project status

- Implemented: content-addressable storage (BLAKE3), layered block/file model, ZIO Streams APIs, filesystem and S3 blob stores, media type detection (Tika), structured logging, Prometheus metrics, scan utilities.
- Modules: `core`, `fs`, `s3`, `tika`, `metrics`, `pg` (Postgres integration/tooling).
- Examples: CLI and HTTP gateway walkthroughs for local workflows.
- Planned/next: richer view materialization, replication/healing controls, expanded dashboards and production guides.

## Documentation map

- Architecture: [architecture.md](docs/src/main/mdoc/architecture.md), [binary-store.md](docs/src/main/mdoc/binary-store.md)
- Operations: [logging.md](docs/src/main/mdoc/logging.md), [metrics.md](docs/src/main/mdoc/metrics.md)
- Utilities: [scan.md](docs/src/main/mdoc/scan.md), [file-descriptor-schema.md](docs/src/main/mdoc/file-descriptor-schema.md)
- Examples: [examples/index.md](docs/src/main/mdoc/examples/index.md)
- Index: [docs home](docs/src/main/mdoc/index.md)

## Logging

Graviton uses [ZIO Logging](https://zio.dev/reference/logging/) for structured
output. Each major operation logs start, completion and any errors at `info` and
`error` levels. A correlation ID is attached to every request so entries can be
traced across layers.

Loggers and log levels are configured via ZIO layers. For example, to route logs
through SLF4J:

```scala
import zio.Runtime
import zio.logging.backend.SLF4J

val runtime = Runtime.removeDefaultLoggers >>> SLF4J.slf4j
```

The `ZIO_LOG_LEVEL` environment variable controls the minimum level emitted by
the default console logger.

For verifying log output in tests, use `ZTestLogger` to capture log entries:

```scala
import zio.test.ZTestLogger

val program =
  for
    _     <- myBinaryStore.exists(BinaryId("1"))
    logs  <- ZTestLogger.logOutput
  yield assertTrue(logs.nonEmpty)

program.provideLayer(ZTestLogger.default)
```

## Development

Integration tests that rely on Docker are gated behind the `TESTCONTAINERS`
environment variable:

```bash
TESTCONTAINERS=1 ./sbt test
```

This flag is enabled automatically in CI.
