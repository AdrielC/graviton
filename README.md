# Graviton

[DOCS](https://adrielc.github.io/graviton)

Graviton is a modular content-addressable storage runtime built on the ZIO ecosystem. The repository is structured as a multi-module build so that pure data types, streaming utilities, runtime ports, transport layers, and backend implementations evolve independently.

## Building locally

```bash
./sbt compile
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
npm ci
npm run docs:dev
```

Try the **[CAS Playground](https://adrielc.github.io/graviton/cas-playground)** on the documentation site to explore chunking, hashing, and deduplication in your browser.

## Module map

```
graviton/
├─ modules/
│  ├─ graviton-core/        # pure domain types: hashing, keys, locators, ranges, manifests
│  ├─ graviton-streams/     # ZIO stream combinators, chunkers, hashing pipelines, scan helpers
│  ├─ graviton-runtime/     # runtime ports (BlobStore, BlockStore, policies, metrics)
│  ├─ graviton-cli/         # SBT runnable CLI: ingest / stat / get / verify (filesystem blocks + in-memory manifests)
│  ├─ protocol/
│  │  ├─ graviton-proto/    # protobuf definitions for the public RPC APIs
│  │  ├─ graviton-grpc/     # zio-grpc service stubs (server wiring to the runtime is still in progress)
│  │  └─ graviton-http/     # zio-http routes (blob upload/download, dashboard helpers)
│  ├─ backend/
│  │  ├─ graviton-s3/       # AWS SDK v2 backed object store bindings
│  │  ├─ graviton-pg/       # PostgreSQL powered manifest/metadata stores
│  │  └─ graviton-rocks/    # RocksDB backed key-value primitives with metrics adapters
│  └─ server/
│     └─ graviton-server/   # HTTP server, backend selection (fs / S3|MinIO), Postgres manifests, metrics
└─ docs/                    # VitePress documentation (architecture, manifests, API surface, operations)
```

Graviton depends on `dev.zio %% zio-blocks-schema` (published to Maven Central at `0.0.32`) for its content-defined chunking and register-backed schema primitives; no submodule or local publish is required.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for workflow guidance, coding standards, and required validation steps before opening a pull request.

## High level flow

1. **Chunk and hash** – chunkers in `graviton-streams` split byte streams into blocks; `graviton-core` supplies hashers and `BinaryKey` derivation.
2. **Derive locators** – pure locator strategies in `graviton-core` map keys to storage paths (S3 prefixes, filesystem layout, etc.).
3. **Persist** – `BlockStore` implementations write canonical blocks; `CasBlobStore` builds manifests and uses `BlobManifestRepo` (Postgres in the server, or in-memory for the CLI / `Graviton` helpers).
4. **Index and track** – `ReplicaIndex` and related ports exist for replication metadata; several paths are still stubs or roadmap (see [Replication](docs/runtime/replication.md)).
5. **Serve** – `graviton-server` exposes an HTTP API for blobs, health, and dashboard endpoints; gRPC is defined in protos and libraries but not yet exposed as a full production server entrypoint alongside HTTP.

Consult the [architecture document](docs/architecture.md) for a detailed walkthrough of the modules, runtime wiring, and extension points.
