# Modules Overview

Graviton is split into focused SBT sub-projects. Each module owns a specific slice of the system so that storage backends, protocol surfaces, and UI experiences can evolve independently. Use this page as a map before diving into the detailed module guides.

## High-level Layout

- **Core** (documented separately): pure data types, codecs, and algebraic structures.
- **Runtime**: storage ports, policies, and operational concerns that wire pure logic to effectful infrastructure.
- **Streams**: ZIO Stream utilities that implement chunking, hashing, and signal processing primitives.
- **Protocol**: shared API models and transport-specific servers (gRPC + HTTP).
- **Backend adapters**: concrete integrations (PostgreSQL, S3, RocksDB) that satisfy runtime ports.
- **Frontend**: Scala.js dashboard used in the interactive documentation demo.

## Quick Links

- [Backend adapters](/modules/backend)
- [Runtime module](/modules/runtime)
- [Streams utilities](/modules/streams)
- [Protocol stack](/modules/protocol)
- [Scala.js frontend](/modules/frontend)
- [Apache Tika module](/modules/tika)

Each page outlines the responsibilities, current implementation status, and follow-up tasks needed for v0.1.0. Cross-reference the refined TODO list in `AGENTS.md` for the latest engineering priorities.
