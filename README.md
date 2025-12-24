# Graviton

[DOCS](https://adrielc.github.io/graviton)

Graviton is a modular content-addressable storage runtime built on the ZIO ecosystem. The repository is structured as a multi-module build so that pure data types, streaming utilities, runtime ports, transport layers, and backend implementations evolve independently.

## Building locally

```bash
sbt compile
```

To execute the formatter and the unit suites:

```bash
TESTCONTAINERS=0 ./sbt scalafmtAll test
```

The documentation site is powered by VitePress and includes an interactive Scala.js frontend. Run it locally with:

```bash
# Build the interactive frontend
sbt buildFrontend

# Start the documentation server
cd docs
npm install
npm run docs:dev
```

Visit the [interactive demo](/demo) to explore Graviton's capabilities in your browser!

## Module map

```
graviton/
├─ modules/
│  ├─ graviton-core/        # pure domain types: hashing, keys, locators, ranges, manifests, union-find
│  ├─ graviton-streams/     # ZIO stream combinators, hashing pipelines, scan/timeseries helpers
│  ├─ graviton-runtime/     # runtime ports (BlobStore, RangeTracker, policies, constraints, metrics facade)
│  ├─ protocol/
│  │  ├─ graviton-proto/    # protobuf definitions for the public RPC APIs
│  │  ├─ graviton-grpc/     # zio-grpc powered services that implement the protocol surfaces
│  │  └─ graviton-http/     # zio-http routes for parity with the gRPC services
│  ├─ backend/
│  │  ├─ graviton-s3/       # AWS SDK v2 backed object store bindings
│  │  ├─ graviton-pg/       # PostgreSQL powered object, key-value, and index stores
│  │  └─ graviton-rocks/    # RocksDB backed key-value primitives with metrics adapters
│  └─ server/
│     └─ graviton-server/   # application wiring, shardcake coordination, protocol servers, metrics export
└─ docs/                    # VitePress documentation (architecture, manifests, API surface, operations)
```

The [`modules/zio-blocks`](modules/zio-blocks) directory is a git submodule that hosts the content-defined chunking primitives consumed by `graviton-streams`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for workflow guidance, coding standards, and required validation steps before opening a pull request.

## High level flow

1. **Chunk and hash** – `graviton-streams` adapters coordinate the chunking pipeline and drive `MultiHasher` instances defined in `graviton-core`.
2. **Derive locators** – the pure locator strategies in `graviton-core` derive stable storage paths from binary keys.
3. **Persist** – backend modules implement `MutableObjectStore` from `graviton-runtime` and provide concrete persistence logic.
4. **Index and track** – range trackers and replica indexes coordinate with backends via optimistic compare-and-set semantics.
5. **Serve** – the `graviton-server` module composes HTTP and gRPC frontends, enforces policies, and exports metrics.

Consult the [architecture document](docs/architecture.md) for a detailed walkthrough of the modules, runtime wiring, and extension points.
