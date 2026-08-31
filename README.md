# Graviton

[![CI](https://github.com/AdrielC/graviton/actions/workflows/ci.yml/badge.svg)](https://github.com/AdrielC/graviton/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-live-00a86b)](https://adrielc.github.io/graviton/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Graviton is a typed, streaming content-addressable storage runtime for Scala 3 and ZIO. It chunks blobs into bounded blocks, derives cryptographic content keys, deduplicates writes, persists versioned manifests, and streams bytes back through pluggable storage ports.

Graviton's boundary is deliberately narrow: bytes, cryptographic content identity, integrity, and storage. It does not define documents, document versions, business metadata, search, or workflows. Downstream systems consume opaque Graviton content IDs and byte streams without extending the storage runtime's domain. See [Scope and product boundary](docs/scope.md).

This is pre-1.0 software. The embedded runtime and single-node filesystem server have executable lifecycle, restart, backup, restore, and integrity proof for controlled use. Shared and distributed deployments still require qualification against the exact identity provider, ingress, database, object store, coordinator, network, workload, and failure model.

The documentation tracks current `main`, which is ahead of the latest published release, `v0.7.0`. Features added after that tag are not present in the `v0.7.0` Maven artifacts. See the [implementation status ledger](docs/implementation-status.md) before choosing a dependency or deployment path.

| Capability | Availability | Evidence boundary |
| --- | --- | --- |
| Embedded and filesystem CAS | Released in `v0.7.0` | Real lifecycle, restart, fsync, integrity, backup, restore, and GC tests |
| HTTP v1, JVM SDK, streaming gRPC, PDF-aware ingest | Released in `v0.7.0` | Contract, socket, packaged-process, and external-consumer tests; logical 1 TiB proof is not a physical transfer |
| S3-compatible blocks plus PostgreSQL manifests | Released in `v0.7.0`, integration-tested | Real MinIO and PostgreSQL CI; provider semantics and capacity remain target work |
| Replication, fixed 2+1 erasure, Shardcake locality | Released in `v0.7.0`, optional | Library and local failure drills; declared failure domains still require physical acceptance |
| RocksDB | Released in `v0.7.0` as typed KV | Not a block store or complete CAS backend |
| Multi-tenant storage and tenant laws | Implemented on `main`, optional | Authenticated isolation, RLS, retained quotas, and shared trust domains; not in `v0.7.0` |
| Redis or Valkey distributed admission | Implemented on `main`, optional | Atomic transfer limits and HTTP request/delivered-egress quotas; not wired as a distributed gRPC traffic meter |
| GVM4 metadata, manifest proof, tenant snapshots, cold-block scrub | Implemented on `main` | Clean-store only; no legacy reader or backfill path |
| Operator control plane and Production Telemetry v1 | Implemented on `main`, optional | Typed snapshots, 15-panel dashboard, 15 recording rules, 16 alerts, and 16 qualification gates; five gates remain target-required |

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
- locally published module metadata consumed from an unrelated sbt build
- every public binary artifact contains executable definitions and no unsupported-operation markers

The packaged smoke uploads real bytes over HTTP, compares the retrieved file byte-for-byte, exercises a range and `If-None-Match`, runs server-side verification, confirms anonymous denial, and confirms a read-only token cannot upload. It also runs open and bearer-protected 3 MiB gRPC lifecycles through the assembled JAR, validates every streamed byte, and exercises health, metadata, inventory, inspection, and deletion. The HTTP SDK suite separately proves a lazy logical 1 TiB request contract and a real 32 MiB upload/download/verify lifecycle over a socket.

For the independently addressable three-domain storage topology:

```bash
./scripts/demo-three-domain.sh up
./scripts/qualify-three-domain.sh | jq .
```

That drill stops the preferred object-service process, proves remote reconstruction, destroys a second target's complete Docker volume, recreates it empty, waits for repair convergence, and checks the exact payload again. Prometheus and Grafana read the server's live metrics rather than fixture data. See [Failure-domain durability](docs/runtime/replication.md).

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

Default data is persisted below `.graviton/`. Select `s3` or `minio` for S3-compatible blocks with PostgreSQL manifests. The only HTTP blob contract is `/api/v1/blobs`.

For the built-in DataStar operator console, enable the local-only surface and open it directly from the running server:

```bash
GRAVITON_CONSOLE_ENABLED=true ./sbt "server/run"
open http://127.0.0.1:8081/console
```

The console sends each file as one raw streaming request body, reports the real CAS reuse result, and downloads through the canonical blob API. Its Operations view reports live storage readiness, transfer capacity, Shardcake placement, durability, dependencies, and process traffic through a typed ZIO Blocks Datastar refresh. Process counters come from the same metric registry exported at `/metrics`; there is no generated health state. Filesystem folder and file references are atomically persisted as a bounded ZIO Blocks JSON document below `GRAVITON_FS_ROOT/catalog/`, so they survive a fresh server process while still pointing to immutable CAS content. Enabling the console binds the HTTP listener to loopback unless remote binding is explicitly allowed. To exercise two real nodes with shared PostgreSQL, MinIO, and Shardcake placement, run `./scripts/demo-shardcake-local.sh up`; its published host ports are also loopback-only. See [the local Shardcake topology](deploy/local-shardcake/README.md).

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
        │   ├── ReplicatedBlockStore
        │   └── ErasureBlockStore
        │       └── three named S3 or Ceph RGW shard targets
        └── BlobManifestRepo
            ├── in-memory reference implementation
            ├── FsBlobManifestRepo
            └── PgBlobManifestRepo
        ├── TransferFootprint       named live-byte ownership algebra
        ├── TransferBudget          process, tenant, and backend admission
        ├── ManifestIntegrity       streaming versioned manifest proofs
        └── RepairJournal           durable cursor and dead-letter state

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
npm ci --prefix docs
./sbt contentLab/test pdfContentLab/test docs/mdoc checkDocSnippets buildDocsAssets
npm run docs:build --prefix docs
```

CI adds real PostgreSQL and MinIO services, the clean external consumer, packaged-server smoke tests, compatibility policy, dependency review, and docs verification. See [BUILD_AND_TEST.md](BUILD_AND_TEST.md) for focused commands.

The [documentation site](https://adrielc.github.io/graviton/) retains the Matrix rain, CAS playground, pipeline explorer, and live connection console. The playground streams local files through a dedicated Scala.js analyzer, maps exact cross-file block reuse, and loads the separately linked ZIO PDF editor only for confirmed PDFs. It never pretends to be a hosted Graviton server or to persist data.

## Operations and releases

- [Production readiness](docs/ops/production-readiness.md)
- [Operator Kit](docs/ops/operator-kit.md)
- [Credential rotation](docs/ops/credential-rotation.md)
- [Deployment](docs/ops/deployment.md)
- [Backup and restore](docs/ops/backup-restore.md)
- [Configuration](docs/guide/configuration-reference.md)
- [HTTP API](docs/api/http.md)
- [Performance measurement](docs/ops/performance.md)
- [Shardcake upload locality](docs/modules/shardcake.md)
- [Multi-tenant storage](docs/runtime/multi-tenancy.md)

A `v*` tag builds the tested JAR, checksums, SPDX SBOM, provenance attestations, multi-architecture GHCR image, GitHub release, and signed Maven Central modules. The release workflow fails closed when publication credentials are configured but invalid.

## Remaining boundaries

Current `main` implements durable resumable HTTP uploads, backend-native cursor inventory, bounded manifest inspection, versioned blob metadata, typed storage errors, backend and fault-law kits, authenticated tenant routing, retained and traffic quotas, hard process-wide memory admission, durable tenant-domain snapshots, cold-block convergence, S3 quarantine inventory and restore, portable telemetry, and executable local qualification. The additions after `v0.7.0` require a later release before they can be consumed from Maven Central. Physical power loss, disk corruption, real IdP and ingress behavior, Ceph and S3 semantics, Redis or Valkey and database failover, zone impairment, and target capacity remain environment-specific acceptance work.

## License

Apache License 2.0. See [LICENSE](LICENSE).
