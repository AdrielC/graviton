# Graviton Documentation

Graviton is a content-addressable storage runtime that provides a stable ingest and retrieval pipeline for large binary payloads. The system is modular by design so that hashing, chunking, persistence, replication, and protocol concerns evolve independently.

## Quick start

1. Build all modules: `sbt compile`.
2. Run the unit test suites: `TESTCONTAINERS=0 ./sbt scalafmtAll test`.
3. Launch the documentation site: `cd docs && npm install && npm run docs:dev`.

## Next steps

- Read the [architecture guide](./architecture.md) for a module-by-module breakdown.
- Follow the [end-to-end upload walkthrough](./end-to-end-upload.md) to understand how chunks travel through the system.
- Browse the [API reference](./api.md) for gRPC and HTTP surfaces.
