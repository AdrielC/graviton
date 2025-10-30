# Getting Started

Welcome to Graviton — a modular, content-addressable storage runtime built on ZIO.

## What is Graviton?

Graviton provides a stable ingest and retrieval pipeline for large binary payloads. The system is designed with modularity at its core, allowing hashing, chunking, persistence, replication, and protocol concerns to evolve independently.

## Key Features

- **Content-Addressable Storage**: Automatic deduplication through content-defined chunking
- **Modular Architecture**: Pure domain logic separated from effectful runtime code
- **Multiple Backends**: S3, PostgreSQL, RocksDB support out of the box
- **ZIO-Powered**: Built on ZIO for composable, type-safe effects
- **Protocol Flexibility**: Both gRPC and HTTP endpoints
- **Observable**: Prometheus metrics, structured logging, and tracing

## Quick Start

### Prerequisites

- Java 21 or higher
- SBT 1.9+
- Node.js 20+ (for documentation)
- Docker (optional, for TestContainers)

### Build from Source

```bash
# Clone the repository
git clone https://github.com/AdrielC/graviton.git
cd graviton

# Compile all modules
sbt compile

# Run tests (without TestContainers)
TESTCONTAINERS=0 ./sbt scalafmtAll test

# Run with TestContainers
TESTCONTAINERS=1 ./sbt test
```

### Run the Documentation

```bash
cd docs
npm install
npm run docs:dev
```

The documentation will be available at `http://localhost:5173`.

## Your First Upload

Here's a minimal example of using Graviton's core APIs:

```scala
import graviton.core.*
import graviton.runtime.*
import zio.*

// Create a simple blob store
val store: BlobStore = ???

// Upload some bytes
val upload = for {
  // Compute hash and derive key
  bytes <- ZIO.succeed("Hello, Graviton!".getBytes)
  key   <- HashAlgo.SHA256.hash(bytes)
  
  // Store the blob
  _ <- store.put(key, Chunk.fromArray(bytes))
  
  // Retrieve it back
  retrieved <- store.get(key)
} yield retrieved

// Run the effect
Unsafe.unsafe { implicit unsafe =>
  Runtime.default.unsafe.run(upload)
}
```

## What's Next?

- **[Installation Guide](./installation)** — Set up Graviton in your environment
- **[Architecture Overview](../architecture)** — Understand the module structure
- **[Core Concepts](../core/schema)** — Deep dive into schemas, ranges, and scans
- **[API Reference](../api)** — Explore gRPC and HTTP endpoints

## Need Help?

- [GitHub Issues](https://github.com/AdrielC/graviton/issues) — Report bugs or request features
- [Contributing Guide](../dev/contributing) — Learn how to contribute

::: tip
Start with the [Architecture Guide](../architecture) to understand how Graviton's modules fit together.
:::
