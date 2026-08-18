# Getting Started

Welcome to Graviton — a modular, content-addressable storage runtime built on ZIO.

## What is Graviton?

Graviton provides a stable ingest and retrieval pipeline for large binary payloads. The system is designed with modularity at its core, allowing hashing, chunking, persistence, replication, and protocol concerns to evolve independently.

## Key Features

- **Content-Addressable Storage**: Automatic deduplication through content-defined chunking
- **Modular Architecture**: Pure domain logic separated from effectful runtime code
- **Multiple Backends**: Filesystem and S3-compatible block stores with Postgres manifest repos in `graviton-server`; in-memory helpers (`Graviton.inMemory`, test kits) for libraries and tests
- **ZIO-Powered**: Built on ZIO for composable, type-safe effects
- **Protocol Flexibility**: HTTP blob API today; gRPC contracts and libraries exist, but the main server entrypoint is HTTP (see [HTTP API](../api/http.md) and [gRPC](../api/grpc.md))
- **Observable**: Prometheus-style metrics and structured logging (distributed tracing is not wired as a first-class feature yet)

## Quick Start

### Prerequisites

- Java 21 or higher
- sbt 1.11+
- Node.js 20+ (for documentation and the interactive demo)
- Docker (optional, for TestContainers-driven integration tests)

### Build from Source

```bash
# Clone the repository
git clone https://github.com/AdrielC/graviton.git
cd graviton

# Compile all modules
./sbt compile

# Run formatting + the full JVM/JVM JS test matrix (without TestContainers)
TESTCONTAINERS=0 ./sbt scalafmtAll test

# (Optional) Exercise container-backed integration tests
TESTCONTAINERS=1 ./sbt test
```

### Run the documentation site

The published docs include a **[CAS Playground](https://adrielc.github.io/graviton/cas-playground)** that runs in the browser without a Scala.js rebuild. For the full Laminar **Scala.js Dashboard** route (`/demo` on the docs site), build the JS bundle before launching VitePress so that page can load `main.js` without errors.

```bash
# From the project root
./sbt buildFrontend       # copies Scala.js output into docs/public/js/

cd docs
npm install               # first run only
npm run docs:dev
```

Once VitePress boots at `http://localhost:5173`, open **CAS Playground** or **Scala.js Dashboard** in the nav and confirm interactive content loads. If you deploy the docs somewhere with a sub-path (for example GitHub Pages), the loader picks up the correct base URL automatically—no manual tweaks required.

::: tip No Scala.js bundle?
If the dashboard reports _“Interactive Demo Not Available”_, rebuild it with `./sbt buildFrontend` and refresh the page. The bundle is committed for convenience, but rebuilding ensures it tracks your local Scala sources.
:::

## Your First Upload

Here's a minimal example using the `Graviton` facade with an in-memory block store and inline manifest repo (no external services required). `InMemoryBlobStore` lives under **test** sources only; prefer `Graviton.inMemory` for quick experiments.

```scala
import graviton.runtime.Graviton
import zio.*
import zio.stream.*

object Demo extends ZIOAppDefault:
  override def run =
    for
      g      <- Graviton.inMemory()
      write  <- ZStream.fromIterable("Hello, Graviton!".getBytes("UTF-8")).run(g.blobStore.put())
      bytes  <- g.blobStore.get(write.key).runCollect
      output <- ZIO.attempt(new String(bytes.toArray, "UTF-8"))
      _      <- Console.printLine(output)
    yield ()
```

## What's Next?

- **[Pipeline Explorer](../pipeline-explorer.md)** — Compose transducer stages interactively in the browser
- **[Installation Guide](./installation.md)** — Set up Graviton in your environment
- **[Configuration Reference](./configuration-reference.md)** — Every env var the current server reads (with defaults)
- **[CLI & Server Usage](./cli.md)** — Run the server and interact via curl
- **[Binary Streaming Guide](./binary-streaming.md)** — Learn how blocks, manifests, and chunkers fit together
- **[Transducer Algebra](../core/transducers.md)** — Typed, composable pipeline stages with Record summaries
- **[Architecture Overview](../architecture.md)** — Understand the module structure
- **[Core Concepts](../core/schema.md)** — Deep dive into schemas, ranges, and scans
- **[API Reference](../api.md)** — Explore gRPC and HTTP endpoints

## Need Help?

- [GitHub Issues](https://github.com/AdrielC/graviton/issues) — Report bugs or request features
- [Contributing Guide](../dev/contributing.md) — Learn how to contribute

::: tip
Start with the [Architecture Guide](../architecture.md) to understand how Graviton's modules fit together.
:::
