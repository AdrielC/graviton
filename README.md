# Graviton

[![CI](https://github.com/AdrielC/graviton/actions/workflows/ci.yml/badge.svg)](https://github.com/AdrielC/graviton/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-live-00a86b)](https://adrielc.github.io/graviton/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Graviton is a typed, streaming content-addressable storage runtime for Scala 3 and ZIO. It chunks blobs into bounded blocks, derives cryptographic content keys, deduplicates writes, persists versioned manifests, and streams bytes back through pluggable storage ports.

This is operational pre-1.0 software. The embedded runtime and single-node filesystem server are ready for controlled use. The shared S3 plus PostgreSQL composition has real integration coverage and backend-wide operation/maintenance coordination, but each operator still owns workload qualification, disaster recovery acceptance, identity-provider configuration, and multi-process rollout testing.

| Capability | Status | Executable evidence |
| --- | --- | --- |
| In-memory CAS | Operational | Round-trip, property, deduplication, stat, and delete suites |
| Filesystem blocks and manifests | Operational | Fsync, atomic publication, restart-safe round trips, health checks, and reversible GC tests |
| Cross-store maintenance atomicity | Operational in built-in compositions | Full-operation shared permits, exclusive GC leases, filesystem locks, PostgreSQL advisory locks, interruption cleanup, and an upload-versus-GC race proof |
| CLI lifecycle | Operational | `ingest`, `stat`, `get`, `verify`, `delete`, `list`, and conservative GC |
| Versioned HTTP API | Operational | Upload, pagination, metadata, verification, ranges, conditional reads, retrieval, and deletion tests |
| PDF-aware ingest | Operational | Typed `application/pdf` routing, signature validation, incremental zio-pdf object scanning, bounded fallback, filesystem-CAS probe, and external-consumer proof |
| Scala streaming SDK | Operational | Typed lifecycle, logical 1 TiB laziness contract, real 32 MiB socket round trip, and clean external-consumer compilation |
| Authentication and policy | Operational | RS256 OIDC/JWKS verification, dev HS256 proof, capabilities, CORS, TLS policy, size and rate controls, and chained audit events |
| S3 blocks and PostgreSQL manifests | Integration-tested | Real MinIO and PostgreSQL CI, replica-index persistence, and S3 quarantine/restore coverage |
| Block replication primitive | Operational library surface | Write quorum, validating read fallback, repair, and quorum-health tests |
| Packaging and supply chain | Release-ready | Distroless non-root image, pinned CI, SBOM, checksums, artifact attestations, and clean external-consumer proof |
| Streaming gRPC lifecycle | Operational | Packaged listener, typed SDK, 12 MiB socket lifecycle, bounded frames, public health, authentication, capabilities, rate limiting, and audit |
| Shardcake upload locality | Operational opt-in integration | Typed tenant/session ownership, one-shot direct ZIO HTTP streams, ZIO Blocks MessagePack control envelopes, authenticated manager and node traffic, durable PostgreSQL assignments, two-node drain/reassignment proof, and a singleton manager lease |
| RocksDB key-value module | Operational in scope | Durable typed key-value storage with close/reopen persistence tests; it is not advertised as a blob backend |

## Prove it locally

Prerequisite: JDK 21 or newer.

```bash
git clone https://github.com/AdrielC/graviton.git
cd graviton

./scripts/verify-local-lifecycle.sh
./sbt server/assembly
./scripts/smoke-packaged-server.sh
./scripts/verify-external-consumer.sh
./scripts/audit-published-artifacts.sh
```

Those commands prove four separate boundaries:

- durable CLI operations across fresh JVM processes
- the packaged JAR running the HTTP and gRPC listeners, including open and authenticated HTTP lifecycles
- published module metadata consumed from an unrelated sbt build
- every public binary artifact contains executable definitions and no unsupported-operation markers

The packaged smoke uploads real bytes over HTTP, compares the retrieved file byte-for-byte, exercises a range and `If-None-Match`, runs server-side verification, confirms anonymous denial, and confirms a read-only token cannot upload. It also runs open and bearer-protected 3 MiB gRPC lifecycles through the assembled JAR, validates every streamed byte, and exercises health, metadata, inventory, inspection, and deletion. The HTTP SDK suite separately proves a lazy logical 1 TiB request contract and a real 32 MiB upload/download/verify lifecycle over a socket.

## Run the server

```bash
./sbt "server/run"

# In another terminal
upload="$(curl -fsS -X POST --data-binary @README.md http://localhost:8081/api/v1/blobs)"
blob_id="$(jq -r '.blob.id' <<<"$upload")"
curl -fsS "http://localhost:8081/api/v1/blobs/$blob_id" --output retrieved.md
cmp README.md retrieved.md
curl -fsS -X POST "http://localhost:8081/api/v1/blobs/$blob_id/verify" | jq .
```

Default data is persisted below `.graviton/`. Select `s3` or `minio` for S3-compatible blocks with PostgreSQL manifests. The legacy `/api/blobs` routes remain available with deprecation headers; new clients should use `/api/v1/blobs`.

For the built-in DataStar operator console, enable the local-only surface and open it directly from the running server:

```bash
GRAVITON_CONSOLE_ENABLED=true ./sbt "server/run"
open http://127.0.0.1:8081/console
```

The console sends each file as one raw streaming request body, reports the real CAS reuse result, and downloads through the canonical blob API. Its server-rendered actions use `zio-blocks-datastar` 0.0.51 with the official DataStar 1.0.2 browser runtime. Filesystem folder and file references are atomically persisted as a bounded ZIO Blocks JSON document below `GRAVITON_FS_ROOT/catalog/`, so they survive a fresh server process while still pointing to immutable CAS content. Enabling the console binds the HTTP listener to loopback unless remote binding is explicitly allowed. To exercise two real nodes with shared PostgreSQL, MinIO, and Shardcake placement, run `./scripts/demo-shardcake-local.sh up`; its published host ports are also loopback-only. See [the local Shardcake topology](deploy/local-shardcake/README.md).

Scala applications can use `ai.hylo.graviton.client.GravitonClient` from the `graviton-http` artifact. Upload and download bodies remain streamed, media types use ZIO Blocks, and byte lengths are Iron-refined through 1 TiB. See the [Scala Streaming SDK guide](docs/guide/scala-sdk.md).

`application/pdf` uploads sent to the HTTP API are routed through the `graviton-pdf` module. It validates the PDF signature and uses zio-pdf's incremental object scanner to prefer stable object boundaries without collecting the document. Embedded applications can call `PdfIngest.put` directly. See [PDF-aware ingest](docs/modules/pdf.md).

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
└── CoordinatedBlobStore
    ├── MaintenanceCoordinator
    │   ├── in-process writer-preferring gate
    │   ├── filesystem shared/exclusive lock
    │   └── PostgreSQL shared/exclusive advisory lock
    └── CasBlobStore
        ├── Chunker                fixed, FastCDC, delimiter, or PDF-aware
        ├── BlockStore
        │   ├── InMemoryBlockStore
        │   ├── FsBlockStore
        │   ├── S3BlockStore
        │   └── ReplicatedBlockStore
        └── BlobManifestRepo
            ├── in-memory reference implementation
            ├── FsBlobManifestRepo
            └── PgBlobManifestRepo

Optional multi-node ingress
└── LocalityAwareUpload
    ├── Shardcake control-plane placement
    ├── direct streamed owner transport
    └── owner-local PDF-aware or generic CAS ingest
```

The build keeps pure content types in `graviton-core`, stream transformations in `graviton-streams`, effectful ports in `graviton-runtime`, protocol adapters under `modules/protocol`, and deployment wiring in `graviton-server`.

## Build and verify

```bash
TESTCONTAINERS=0 ./sbt scalafmtCheckAll test
GRAVITON_IT=1 ./sbt "server/testOnly graviton.server.EmbeddedPgFsCasRoundTripSpec"
./sbt docs/mdoc checkDocSnippets buildDocsAssets
npm ci --prefix docs
npm run docs:build --prefix docs
```

CI adds real PostgreSQL and MinIO services, the clean external consumer, packaged-server smoke tests, compatibility policy, dependency review, and docs verification. See [BUILD_AND_TEST.md](BUILD_AND_TEST.md) for focused commands.

The [documentation site](https://adrielc.github.io/graviton/) retains the Matrix rain, CAS playground, pipeline explorer, and live connection console. The bounded CAS lab runs the same `graviton-shared` analyzer on Scala.js and JVM, using Web Crypto and JCA respectively. It never pretends to be a hosted Graviton server or to persist data.

## Operations and releases

- [Production readiness](docs/ops/production-readiness.md)
- [Deployment](docs/ops/deployment.md)
- [Backup and restore](docs/ops/backup-restore.md)
- [Configuration](docs/guide/configuration-reference.md)
- [HTTP API](docs/api/http.md)
- [Performance measurement](docs/ops/performance.md)
- [Shardcake upload locality](docs/modules/shardcake.md)

A `v*` tag builds the tested JAR, checksums, SPDX SBOM, provenance attestations, multi-architecture GHCR image, GitHub release, and signed Maven Central modules. The release workflow fails closed when publication credentials are configured but invalid.

## Remaining boundaries

The highest-value remaining work is resumable HTTP upload contracts, automated replica maintenance, coordinated backup snapshots, long-duration failure injection, and target-environment multi-process rolling-upgrade acceptance. See [ROADMAP.md](ROADMAP.md) for the ordered plan.

## License

Apache License 2.0. See [LICENSE](LICENSE).
