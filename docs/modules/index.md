# Modules Overview

Graviton is split into focused SBT sub-projects. Each module owns a specific slice of the system so that storage backends, protocol surfaces, and UI experiences can evolve independently. Use this page as a map before diving into the detailed module guides.

## High-level Layout

- **Core** (documented separately): pure data types, codecs, algebraic structures, and the [Transducer algebra](../core/transducers.md) for composable pipeline stages.
- **Runtime**: storage ports, policies, and operational concerns that wire pure logic to effectful infrastructure.
- **Streams**: ZIO Stream utilities that implement chunking, hashing, and signal processing primitives — designed to compose with Transducers.
- **PDF**: typed, bounded-memory PDF ingest backed by zio-pdf's incremental structural scanner.
- **Shardcake integration**: opt-in session ownership and direct streamed upload locality for multi-node deployments.
- **Distributed admission**: optional Redis or Valkey leases for cluster-wide service, tenant, and backend transfer fairness.
- **Protocol**: shared API models and transport-specific servers (gRPC + HTTP).
- **Backend adapters**: concrete integrations (PostgreSQL, S3, RocksDB) that satisfy runtime ports.
- **Frontend**: Scala.js operations console for the live HTTP service.

## Quick Links

- [Backend adapters](./backend.md)
- [Runtime module](./runtime.md)
- [Streams utilities](./streams.md)
- [PDF-aware ingest](./pdf.md)
- [Shardcake upload locality](./shardcake.md)
- [Distributed transfer admission](./distributed-admission.md)
- [Protocol stack](./protocol.md)
- [Scala.js frontend](../modules/frontend.md)
- [Apache Tika module](./tika.md)

Each page outlines the responsibilities, current implementation status, and remaining engineering work. See the repository `ROADMAP.md` for current priorities.
