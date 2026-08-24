# Roadmap

Graviton is pre-1.0. This roadmap distinguishes working foundations from the remaining release and distributed-systems work.

## Operational now

- Typed blob, block, manifest, range, and attribute models
- Fixed, FastCDC, and delimiter chunkers over bounded ZIO Streams pipelines
- Content-key derivation, block deduplication, framed manifests, retrieval, stat, verification, and manifest deletion
- In-memory and restart-safe filesystem CAS compositions
- S3-compatible block storage and PostgreSQL manifest storage with integration coverage
- CLI lifecycle and pre-1.0 HTTP object lifecycle
- Prometheus text metrics, structured logging, docs snippets, a live Scala.js operations console, and GitHub Pages delivery

## 0.1 release gates

- Stabilize public runtime package names and document source and binary compatibility expectations
- Publish signed artifacts and verify consumption from a clean external sbt project
- Version the HTTP API and finalize structured error codes
- Complete authentication and authorization wiring for non-development deployments
- Add an upgrade policy for framed manifests and PostgreSQL schema migrations
- Publish a reproducible benchmark harness before advertising throughput or latency numbers
- Define support boundaries for JDK, Scala, ZIO, PostgreSQL, and S3-compatible providers

## Storage and reliability

- Promote the durable RocksDB key-value adapter into a complete CAS block backend
- Add replica-index persistence, repair planning, quarantine, and reconciliation jobs
- Add range reads and conditional requests to the HTTP surface
- Add garbage collection for blocks no longer reachable from any manifest
- Add configurable compression and authenticated encryption to the frame format
- Exercise power-loss and partial-write recovery for every durable backend

## Protocols and clients

- Bring the runnable gRPC server to feature parity with the HTTP blob lifecycle
- Publish small Scala client artifacts with compatibility tests
- Add multipart and resumable upload acceptance suites
- Define stable pagination, idempotency, and retry contracts

## Showcase and documentation

- Keep capability tables tied to source and executable tests
- Keep the public UI tied to operational endpoints and surface connection or storage failures directly
- Add architecture decision records for persistence, deletion, and compatibility guarantees
- Publish real benchmark results only with hardware, dataset, configuration, and command provenance
