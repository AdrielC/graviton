# Getting Started

Welcome to Graviton, a modular, content-addressable storage runtime built on ZIO.

## What is Graviton?

Graviton provides an ingest and retrieval pipeline for large binary payloads. The system keeps hashing, chunking, persistence, and protocol concerns in separate modules; replica coordination remains roadmap work.

## Key Features

- **Content-Addressable Storage**: Cryptographic keys and automatic block deduplication across fixed or content-defined chunks
- **Modular Architecture**: Pure domain logic separated from effectful runtime code
- **Multiple Backends**: Restart-safe filesystem blocks and manifests, S3-compatible blocks, PostgreSQL manifests, and in-memory helpers
- **ZIO-Powered**: Built on ZIO for composable, type-safe effects
- **Protocol Flexibility**: HTTP on `8081` and streaming gRPC on `9090` are mounted by the same server process (see [HTTP API](../api/http.md) and [gRPC](../api/grpc.md))
- **Observable**: Prometheus-style metrics and structured logging (distributed tracing is not wired as a first-class feature yet)

## Quick Start

### Prerequisites

- Java 21 or higher
- The repository's included sbt launcher
- Node.js 20+ (for documentation and the operations console)
- Docker (optional, for TestContainers-driven integration tests)

### Build from Source

```bash
# Clone the repository
git clone https://github.com/AdrielC/graviton.git
cd graviton

# Compile all modules
./sbt compile

# Run formatting + the full JVM/Scala.js test matrix (without TestContainers)
TESTCONTAINERS=0 ./sbt scalafmtAll test

# (Optional) Exercise container-backed integration tests
TESTCONTAINERS=1 GRAVITON_IT=1 GRAVITON_MINIO_IT=1 ./sbt test
```

### Prove the local lifecycle

```bash
./scripts/verify-local-lifecycle.sh
```

This runs ingest, stat, get, and verify through separate CLI JVMs, then compares the retrieved file byte-for-byte.

### Run the local server

The default server is self-contained and persists blocks plus manifests below `.graviton/`:

```bash
./sbt "server/run"

# In another terminal
BLOB_ID="$(curl -fsS -X POST --data-binary @README.md http://localhost:8081/api/v1/blobs | jq -r '.blob.id')"
curl -fsSI "http://localhost:8081/api/v1/blobs/$BLOB_ID"
curl -fsS -X POST "http://localhost:8081/api/v1/blobs/$BLOB_ID/verify" | jq .
curl -fsS "http://localhost:8081/api/v1/blobs/$BLOB_ID" --output /tmp/graviton-readme.md
cmp README.md /tmp/graviton-readme.md
```

The same process serves the gRPC blob lifecycle on `localhost:9090`. Its upload stream is the session, so clients do not manage a separate session identifier.

Use the S3 or MinIO backend when you want S3-compatible blocks with PostgreSQL manifests.

### Run the documentation site

The docs include a Laminar **Connect Your Server** console at `/demo` and a separate bounded CAS Playground powered by `graviton-shared`. Build both JS modules before launching VitePress, then run Graviton locally if you want to connect the operations console. The published GitHub Pages site has no hosted backend and starts disconnected; the CAS Playground computes locally through Scala.js and Web Crypto.

```bash
# From the project root
./sbt buildContentLab buildFrontend

cd docs
npm ci
npm run docs:dev
```

Once VitePress boots at `http://localhost:5173`, open **Connect Your Server** in the nav. Upload a file, inspect its persisted manifest, verify it on the server, download it, and delete the manifest. If you deploy the docs somewhere with a sub-path, the loader picks up the correct asset base URL automatically.

::: tip No Scala.js bundle?
If the console reports that its bundle is unavailable, rebuild it with `./sbt buildFrontend`. If the CAS Playground reports that Scala.js is unavailable, run `./sbt buildContentLab`. Then refresh the page.
:::

## Your First Upload

Here's a minimal file-to-file example using the durable `Graviton` facade. Neither ingest nor retrieval collects the file in memory.

```scala
import graviton.runtime.Graviton
import zio.*
import zio.stream.*

import java.nio.file.{Files, Paths}

object Example extends ZIOAppDefault:
  override def run =
    for
      g         <- Graviton.fs(Paths.get(".graviton"))
      write     <- g.ingestFile(Paths.get("README.md"))
      _         <- g.stream(write.key).run(ZSink.fromFileName("/tmp/graviton-README.md"))
      identical <- ZIO.attempt(Files.mismatch(Paths.get("README.md"), Paths.get("/tmp/graviton-README.md")) == -1L)
      _         <- Console.printLine(s"${write.key.bits.render} identical=$identical")
    yield ()
```

## What's Next?

- **[CAS Playground](../cas-playground.md)**: Compute real content IDs and repeated blocks in the browser
- **[Connect Your Server](../demo.md)**: Operate the HTTP lifecycle against a Graviton endpoint you provide
- **[Installation Guide](./installation.md)**: Set up Graviton in your environment
- **[Configuration Reference](./configuration-reference.md)**: Every env var the current server reads (with defaults)
- **[CLI & Server Usage](./cli.md)**: Run the server and interact via curl
- **[Binary Streaming Guide](./binary-streaming.md)**: Learn how blocks, manifests, and chunkers fit together
- **[Transducer Algebra](../core/transducers.md)**: Typed, composable pipeline stages with Record summaries
- **[Architecture Overview](../architecture.md)**: Understand the module structure
- **[Core Concepts](../core/schema.md)**: Deep dive into schemas, ranges, and scans
- **[API Reference](../api.md)**: Explore gRPC and HTTP endpoints

## Need Help?

- [GitHub Issues](https://github.com/AdrielC/graviton/issues): Report bugs or request features
- [Contributing Guide](../dev/contributing.md): Learn how to contribute

::: tip
Start with the [Architecture Guide](../architecture.md) to understand how Graviton's modules fit together.
:::
