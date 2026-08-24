# Graviton

[![CI](https://github.com/AdrielC/graviton/actions/workflows/ci.yml/badge.svg)](https://github.com/AdrielC/graviton/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-live-00a86b)](https://adrielc.github.io/graviton/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Graviton is a typed, streaming content-addressable storage runtime for Scala 3 and ZIO. It chunks blobs into bounded blocks, derives cryptographic content keys, deduplicates writes, persists versioned manifests, and streams bytes back through pluggable storage ports.

It is a working pre-1.0 library, not a finished distributed storage product. The table below separates operational paths from partial and planned work.

| Capability | Status | Evidence |
| --- | --- | --- |
| In-memory CAS | Operational | Round-trip, property, deduplication, stat, and delete suites |
| Filesystem blocks and manifests | Operational | Atomic writes and restart-safe round-trip tests |
| CLI lifecycle | Operational | `ingest`, `stat`, `get`, `verify`, and `delete` across JVM invocations |
| Default HTTP server | Operational, pre-1.0 | Self-contained filesystem CAS plus live process counters |
| HTTP blob lifecycle | Operational, pre-1.0 | Upload, durable inventory, manifest inspection, server-side verification, retrieval, and deletion contract tests |
| S3 blocks and PostgreSQL manifests | Integration-tested | Container-gated CI suites |
| gRPC server parity | Partial | Contracts, generated code, clients, and service implementations exist |
| RocksDB | Partial | Durable key-value adapter works; it is not wired as a CAS block backend |

## See it work

Prerequisite: JDK 21 or newer.

```bash
git clone https://github.com/AdrielC/graviton.git
cd graviton
./scripts/verify-local-lifecycle.sh
```

The verification script performs an ingest, starts fresh CLI JVMs for stat, retrieval, and verification, then compares the retrieved file byte-for-byte. It prints the stable blob ID and the generated store path so you can inspect the blocks and framed manifest.

The HTTP server also runs with no external services by default:

```bash
./sbt "server/run"

# In another terminal
curl -fsS -X POST --data-binary @README.md http://localhost:8081/api/blobs
curl -fsS http://localhost:8081/api/stats
```

Default server data is persisted below `.graviton/`. Select `s3` or `minio` when you want S3-compatible blocks with PostgreSQL manifests.

Blob IDs are explicit and round-trippable:

```text
sha-256:<hex-digest>:<byte-length>
```

## Embed it

```scala
import graviton.runtime.Graviton
import zio.*

import java.nio.file.Paths

object Example extends ZIOAppDefault:
  override def run =
    for
      writer   <- Graviton.fs(Paths.get(".graviton"))
      stored   <- writer.ingestFile(Paths.get("report.pdf"))
      reader   <- Graviton.fs(Paths.get(".graviton"))
      verified <- reader.verify(stored.key)
      _        <- Console.printLine(s"${stored.key.bits.render} verified=$verified")
    yield ()
```

`Graviton.fs` persists both blocks and manifests. `Graviton.inMemory` provides the same logical API for tests and short-lived applications.

## Architecture

```text
BlobStore
└── CasBlobStore
    ├── Chunker                fixed, FastCDC, or delimiter
    ├── BlockStore
    │   ├── InMemoryBlockStore
    │   ├── FsBlockStore
    │   └── S3BlockStore
    └── BlobManifestRepo
        ├── in-memory reference implementation
        ├── FsBlobManifestRepo
        └── PgBlobManifestRepo
```

The build keeps pure content types in `graviton-core`, stream transformations in `graviton-streams`, effectful ports in `graviton-runtime`, protocol adapters under `modules/protocol`, and deployment wiring in `graviton-server`.

## Build and verify

```bash
TESTCONTAINERS=0 ./sbt scalafmtAll test
./sbt docs/mdoc checkDocSnippets
./sbt buildDocsAssets
npm ci --prefix docs
npm run docs:build --prefix docs
```

See [BUILD_AND_TEST.md](BUILD_AND_TEST.md) for focused commands and container-backed integration setup. The [documentation site](https://adrielc.github.io/graviton/) includes a live operations console, architecture guide, HTTP reference, and generated Scaladoc.

## Project direction

The immediate release work is API stabilization, authenticated versioned HTTP contracts, RocksDB CAS integration, replica repair, and published benchmark methodology. See [ROADMAP.md](ROADMAP.md) for the status-driven plan.

## License

Apache License 2.0. See [LICENSE](LICENSE).
