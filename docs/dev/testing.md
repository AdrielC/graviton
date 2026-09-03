# Testing Guide

Graviton separates pure contracts, storage integration, packaged-process proof, and public-consumer proof. Passing a unit suite is not treated as evidence that the assembled server or published artifacts work.

## Fast local validation

```bash
# Deterministic suite without PostgreSQL, MinIO, or Docker
TESTCONTAINERS=0 ./sbt test

# Focused modules
TESTCONTAINERS=0 ./sbt 'core/test' 'streams/test' 'runtime/test'

# One real suite
./sbt 'core/testOnly graviton.core.keys.KeyBitsSpec'

# Formatting and streaming policy
./sbt scalafmtCheckAll
./scripts/check-byte-streaming-hygiene.sh
```

The root project aggregates JVM modules, both `graviton-shared` platforms, and the JVM, Scala.js, and Scala Native `graviton-bytes` artifacts. A root `test` therefore includes both browser and native contract suites.

## Portable hash input contract

Run the canonical byte-encoding contract on every supported runtime when changing `Hashable` or one of its built-in instances:

```bash
./sbt 'bytesJVM/test' 'bytesJS/test' 'bytesNative/test'
```

The suite proves identical UTF-8 bytes, immutable chunk behavior, and multi-chunk framing. It does not treat ZIO Prelude's 32-bit collection `Hash` as a cryptographic digest.

## Shared and browser Scala.js contracts

Run the bounded shared contract and link both browser modules when changing shared types, digest validation, or the docs playground:

```bash
./sbt 'sharedProtocolJVM/test' 'sharedProtocolJS/test'
npm ci --prefix docs
./sbt 'contentLab/test' 'pdfContentLab/test' buildContentLab
```

The shared suite runs the same SHA-256 known vector, empty-input behavior, repeated-block analysis, boundary rejection, and content-key text round trip on JCA and Web Crypto. The content-lab suite covers exact streamed identity, manifest overflow, reusable source opening, and interruption cleanup. `buildContentLab` separately links the streamed file analyzer and bounded ZIO PDF editor used by VitePress.

## Runtime contracts

The repository keeps behavioral tests beside the implementation they exercise. Representative suites include:

| Boundary | Evidence |
| --- | --- |
| Content keys and codecs | `KeyBitsSpec`, `BinaryKeyCodecSpec`, manifest frame specs |
| Chunking and byte bounds | `ChunkerSpec`, `BoundedByteStreamSpec` |
| CAS lifecycle and properties | `CasRoundTripSpec`, `CasPropertySpec`, `InMemoryStoresSpec` |
| Filesystem durability | `FsBlockStoreSpec`, `FsBlobManifestRepoSpec` |
| HTTP contract and policy | `HttpApiSpec`, `HttpSecurityPolicySpec`, `GravitonClientSpec` |
| gRPC socket lifecycle | `GravitonGrpcIntegrationSpec` |
| Backend adapters | `S3BlobStoreSpec`, `PgRangeTrackerSpec`, `RocksKeyValueStoreSpec` |
| Published backend contract | `BlobStoreLawsSpec`, applied to isolated in-memory and filesystem CAS stores |
| Transfer and repair faults | `TransferBudgetSpec`, `FsRepairJournalSpec`, interruption laws |
| Security | capability, rate-limit, OIDC, audit, and claim-mapping specs |

Tests that compare bytes may collect only fixtures whose bound is explicit in the test setup. Production sources are checked separately and may use only the approved bounded collection helpers.

Third-party `BlobStore` implementations can depend on `graviton-backend-laws_3` in the test configuration and mount `BlobStoreLaws.suite`. The law acquisition effect must return a new isolated empty store in a `Scope`; this lets the same suite test real backend resources and verify cleanup.

## Container-backed integration

GitHub Actions is the canonical environment. It starts PostgreSQL 16 and MinIO, applies `modules/backend/graviton-pg/src/main/resources/db/migration/V001__graviton.sql`, creates the S3 block bucket, and runs:

```bash
TESTCONTAINERS=1 \
GRAVITON_IT=1 \
GRAVITON_MINIO_IT=1 \
./sbt test
```

These flags enable `EmbeddedPgFsCasRoundTripSpec` and `MinioCasRoundTripSpec`. Do not interpret a skipped integration suite as backend proof.

## Process and artifact proof

```bash
# Durable operations across fresh CLI JVMs
./scripts/verify-local-lifecycle.sh

# Assemble once, then exercise open and authenticated HTTP and gRPC listeners
./sbt server/assembly
./scripts/smoke-packaged-server.sh

# Publish to an isolated local repository and compile/run an unrelated consumer
./scripts/verify-external-consumer.sh

# Reject empty or unsupported published artifacts
./scripts/audit-published-artifacts.sh
```

The packaged smoke test uploads real bytes, compares downloads byte-for-byte, verifies ranges and preconditions, proves authorization denials, and runs gRPC lifecycle checks against the assembled JAR.

## Documentation

```bash
npm ci --prefix docs
./sbt docs/mdoc checkDocSnippets buildDocsAssets
npm run docs:build --prefix docs
```

If a managed snippet changes, edit its source below `docs/snippets/`, run `./sbt syncDocSnippets`, and rerun `checkDocSnippets`. Generated Scala.js and Scaladoc directories are build output and should not be mistaken for hand-maintained source.

## Coverage

```bash
TESTCONTAINERS=0 ./sbt clean coverage test coverageReport
```

Coverage is a diagnostic, not a release gate by itself. The higher-confidence gates are behavior over real sockets, restart persistence, backend integration, and clean external consumption.

See [Build and Test](https://github.com/AdrielC/graviton/blob/main/BUILD_AND_TEST.md) for the short command reference and [Production Readiness](../ops/production-readiness.md) for the operational evidence matrix.
