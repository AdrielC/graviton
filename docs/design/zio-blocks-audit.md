# ZIO Blocks 0.0.51 Audit

Audit date: 2026-08-26

Graviton baseline: `fc0fee41923c44295f834367175d3df4941b6b3b`

Official release: [ZIO Blocks v0.0.51](https://github.com/zio/zio-blocks/releases/tag/v0.0.51)

## Decision

Graviton should use ZIO Blocks where it gives a concrete contract or performance benefit:

- `MediaType` at HTTP, gRPC, PDF dispatch, JVM SDK, and Scala.js boundaries.
- `Schema` and schema-derived JSON for explicitly bounded control-plane models, with runtime conversion into refined public values. Large manifest inspection must be paginated before schema materialization.
- `DynamicValue`, optics, patches, and migrations for versioned bounded metadata after constructor invariants are encoded.
- SQL and JSONB codecs in an optional PostgreSQL metadata-index pilot with live database proof.
- Typed registers only after the flat pipeline composition exists and beats the simpler tuple implementation in a real benchmark.

Graviton should not replace ZIO Streams, ZIO Scope, FiberRef, or the CAS data plane merely to increase library usage. The Blocks stream module is marked work in progress, and Blocks codecs construct bounded values. Neither is a terabyte blob transport.

## Release-resolution trap

The old build mixed schema `0.017` with media type `0.0.51`. Maven metadata calls `0.017` latest because that malformed version sorts like `0.17`, above `0.0.51`. It was actually published in February 2026, five months before 0.0.51, and its source is the `v0.0.17` code line. There is no `v0.017` GitHub release.

Graviton now:

1. Pins schema, chunk, and media type to `0.0.51`.
2. Overrides transitive resolution so Ivy cannot prefer `0.017` numerically.
3. Consumes zio-pdf RC7, whose JVM and Scala.js publications both use `0.0.51`, and keeps direct schema, chunk, and media-type pins for a deterministic graph.
4. Tests the resolved graph and both JVM and Scala.js contracts.

Use the standard `zio-blocks-*` artifacts. The `next` family does not have matching 0.0.51 Scala.js coverage.

## Important upstream boundary

Adriel's [opaque-wrapper register layout fix](https://github.com/zio/zio-blocks/pull/1578) is merged upstream. It landed on 2026-08-14, after ZIO Blocks 0.0.51 was released on 2026-07-31, so the current Maven Central jars do not contain it yet. A direct schema transform for Iron numeric subtypes can therefore still decode nonzero numbers as zero when an application runs on 0.0.51.

Graviton therefore uses plain primitive wire records inside the Blocks codec and validates every conversion into the public Iron-refined model. Cross-platform tests assert nonzero values, invalid-bound rejection, legacy zio-json compatibility, and structural JSON Schema generation. The generated schema describes the wire shape but does not yet advertise Iron length, range, or pattern constraints. This is a release-compatibility shim, not a second fix. Direct Iron schema derivation can replace it as soon as an official release containing PR #1578 passes the same JVM and Scala.js vectors.

## Media type contract

In 0.0.51, `MediaType.fullType` contains only `mainType/subType`; it drops parameters. `MediaType.parse` also ignores malformed parameter segments.

`MediaTypeText` now supplies the Graviton boundary:

- strict token and parameter validation;
- a 256-character storage bound;
- duplicate-parameter rejection;
- deterministic parameter ordering;
- parameter-preserving HTTP and gRPC rendering;
- an explicitly exported and round-trip-tested ZIO Blocks Schema for bounded media-type metadata values.

The remaining media-type gap is durability. Current manifests do not persist blob attributes, so later downloads still fall back to `application/octet-stream`. That requires a versioned `BlobMetadataV1` manifest extension, not another string field at the transport edge.

## Released module disposition

The module topology comes from the [v0.0.51 build](https://github.com/zio/zio-blocks/blob/v0.0.51/build.sbt).

| Module family | Graviton disposition | Reason |
| --- | --- | --- |
| schema, typeid, maybe | Adopt now for bounded API and metadata contracts | Cross-platform derivation, dynamic schemas, JSON Schema, patches, and migrations are a strong fit. Keep the opaque-wrapper workaround until the next release. |
| mediatype | Adopt now | Replaces weak transport strings and provides generated media types, parsing, file-extension lookup, and matching. |
| chunk | Use only for bounded values through schema dependencies | It is a materialized collection. It is not a blob stream or a replacement for refined ZIO chunks in the data plane. |
| combinators | Use when a type-level shape problem appears | `Concat`, tuples, eithers, and unions are compile-time type combinators, not byte-stream operators. |
| context | Do not replace FiberRef | Blocks Context is a type-indexed value collection. It does not provide fiber-local propagation. |
| scope | Do not replace ZIO Scope | Blocks Scope is synchronous resource safety. Graviton's files, sockets, fibers, and database cursors are effectful and remain ZIO-scoped. |
| streams, async | Do not adopt for the CAS data plane | The official build calls streams work in progress. Existing ZStreams already have scoped cancellation and backpressure. |
| ringbuffer | Evaluate only behind a measured queue benchmark | Existing bounded ZIO queues express the current ownership and interruption semantics clearly. |
| mux | Evaluate for a future framed replication transport | It is not needed for current HTTP or protobuf gRPC surfaces. |
| config plus JSON, YAML, HOCON | Keep zio-config for now | Replacing stable production configuration has no demonstrated benefit. Revisit only with a migration and parity tests. |
| http-model, http-model-schema | Do not mix with zio-http models yet | Graviton already exposes zio-http types. A second HTTP model would increase adapters without improving the transfer path. |
| endpoint, openapi | Pilot for generated control-plane documentation | Promising for one-source endpoint/schema docs, but the operational zio-http routes remain authoritative until parity is proved. |
| sql, sql-zio | Pilot in an optional PostgreSQL metadata index | New in 0.0.51. `DbCodec.jsonb`, `Frag`, `Repo`, and `TransactorZIO` fit typed metadata, but need live PostgreSQL evidence before replacing current JDBC repositories. |
| telemetry, telemetry-otel | Evaluate for schema-aware control-plane telemetry | Keep current runtime metrics until label, allocation, and exporter parity is measured. |
| schema MessagePack, Avro, Thrift, BSON | Use only for bounded versioned envelopes | The codecs construct values from buffers. They must not encode a blob, full ciphertext, or an unbounded manifest collection. |
| schema JSON, YAML, XML, CSV, TOON | Use JSON now; evaluate others only with a product requirement | JSON is the shared API format. Other formats add no current user value. |
| markdown, html, datastar, http-htmx | Do not pull into the storage runtime | These are presentation modules. The existing docs and Scala.js applications have separate concerns. |
| codegen, smithy | Evaluate only if contracts move to generated multi-language SDKs | Current protobuf and shared Scala models already have consumers and executable parity tests. |

There is no released module, class, or format named `BIF`. The real abstractions are `Codec`, `BinaryCodec`, `TextCodec`, `BinaryFormat`, and `TextFormat`. They operate on `ByteBuffer` or `CharBuffer` and are suitable for bounded values.

## Graviton audit findings

### Correct today

- CAS ingest normalizes input to 64 KiB, uses bounded queues, persists one refined block at a time, and spools manifests to disk.
- Blob reads validate one block before emission and stream it onward.
- Filesystem, S3, and PostgreSQL object streams use scoped resources.
- PDF-aware chunking is incremental with bounded carry and block buffers.
- SDK control responses use a named 1 MiB collection bound.
- Scala.js content addressing is explicitly bounded to 8 KiB.

### Corrected in this work

- Unified all ZIO Blocks dependencies on official 0.0.51.
- Added a resolution override for the `0.017` Maven ordering trap.
- Made Blocks schema-derived JSON the real HTTP, JVM SDK, and Scala.js control-plane codec through validated primitive wire records.
- Added a typed API error envelope and structural JSON Schema proof, while keeping refined bounds enforced during every decode.
- Preserved and validated media-type parameters across browser, HTTP, JVM SDK, and gRPC boundaries.
- Strengthened shared content-key parsing with supported algorithms, exact digest lengths, lowercase hex, and nonnegative sizes.
- Moved gRPC expected-length enforcement into the byte stream so mismatch fails before manifest commit.
- Rejected malformed or non-ASCII HTTP `Content-Length` values before the request body is pulled.
- Made PDF ingest preserve canonical media-type parameters and reject a conflicting profile before stream pull.
- Made scodec stream decoding fail on truncated EOF and bounded its carry buffer.
- Made frame encoding emit one encoded frame at a time instead of joining every frame in an upstream chunk.
- Added codec-owned frame preflights for version, block index, AAD size, view count, and encoded-size limits so direct and streaming callers share the same guardrails.
- Enforced manifest-only, canonically derived view keys so the public case-class constructor cannot encode a key that decodes to a different value.
- Replaced singleton-selected refined-size parents whose published TASTy crashed typed downstream `BinaryAttributes` calls. The 0.5 migration documents the narrow early-semver boundary and clean-recompile requirement.
- Made bounded Int and Long arithmetic detect machine overflow before refinement, including the case where a wrapped product would otherwise land inside the accepted range.
- Corrected docs that presented an experimental register benchmark as an operational flat pipeline.

### Production gaps still open

| Priority | Gap | Required shape |
| --- | --- | --- |
| P0 | Garbage collection and backend inventory materialize repository-wide sets and chunks; interrupted or underflowed uploads can leave unreferenced blocks before manifest publication | Cursor/page ports pushed into each backend, plus a durable mark set or sorted streaming merge for GC and orphan cleanup. |
| P0 | Per-blob inspection loads the full manifest and copies every block reference into HTTP/gRPC response models | `streamBlockRefs` or cursor/page inspection with a refined page limit. Keep full-manifest materialization out of server control paths. |
| P0 | Blob metadata, including media type, is not durably persisted | Versioned bounded `BlobMetadataV1` with schema ID, codec version, canonical media type, chunker identity, and migration tests. |
| P1 | `BlockStore.putBlocks` accumulates a complete batch result | Streaming acknowledgements or an Iron-refined maximum batch size. |
| P1 | Several derived legacy schemas bypass key, digest, frame, and transform smart constructors | Validated schemas plus invalid-state round-trip tests before any new binary format adoption. |
| P1 | The legacy `Integral` instance for positive bounded refinements cannot satisfy ordinary numeric laws because zero is outside the domain | Deprecate it in favor of `Ordering` and explicit checked bounded operations, then remove it at the next declared API boundary. |
| P1 | Browser JSON/error bodies and downloads use Fetch `text()` or `blob()` | Limit control bodies and expose a browser `ReadableStream`, native navigation, or File System Access writer for data-plane downloads. |
| P1 | Replica read memory scales with replica count and repair uses detached fibers | Refined replica/concurrency limits, first-valid short circuit, and scoped repair fibers. |
| P2 | `DynamicRecordCodec` cannot losslessly reconstruct binary, numeric widths, sets, tuples, or `None` | Replace schema-agnostic storage with schema-aware bounded metadata JSON. Reject arbitrary binary in control JSON. |
| P2 | Orphan `modules/core`, `modules/db`, and `modules/pg` trees look operational but are not built | Remove, quarantine, or declare and validate them. |

## Recommended next increments

1. Build backend-pushed cursor pagination and streaming GC. Prove first-page latency and fixed-heap behavior on a million-reference fixture.
2. Add `BlobMetadataV1` and persist it in filesystem and PostgreSQL manifests. Return the canonical media type from stat, HTTP, and gRPC.
3. Replace schema-agnostic dynamic JSON with a bounded schema descriptor plus migration registry.
4. Pilot `zio-blocks-sql` in a separate metadata-index module. Generate parameterized JSONB fragments from validated dynamic optics and test them against live PostgreSQL.
5. Revisit direct Iron schema derivation only after an official release containing the external opaque-wrapper fix. Remove the wire workaround only when JVM and Scala.js regression vectors pass.
6. Keep the register pipeline experimental until flat composition is implemented and benchmarked against tuples on the production ingest path.

## Required proof

The focused proof for this adoption is:

```bash
TESTCONTAINERS=0 ./sbt scalafmtAll test
TESTCONTAINERS=0 ./sbt sharedProtocolJVM/test sharedProtocolJS/test
TESTCONTAINERS=0 ./sbt grpc/test http/test frontend/fullLinkJS
TESTCONTAINERS=0 ./sbt core/dependencyTree pdf/dependencyTree
```

The dependency trees must contain `zio-blocks-* 0.0.51` and no `0.017`. API golden vectors must pass on both JVM and Scala.js. Streaming tests must leave no manifest after expected-size mismatch, reject a truncated frame tail, and preserve earlier complete frame output when a later tail is malformed.
