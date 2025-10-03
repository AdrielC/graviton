# Roadmap

This roadmap captures the agreed-upon restructuring for the storage stack and
tracks the sequencing needed to land the new module layout, type system, and
protocol split.  The focus is on stabilising an Iron-first core while keeping
all effectful integrations layered above it.

## 0.1 Foundations

### Highlights of the Rewrite

- **Iron-first type aliases.** Adopt opaque refined types and `refineEither`
  smart constructors in place of value classes or throwing factories.
- **Sealed binary keys.** Consolidate all binary addressing under the sealed
  `BinaryKey` hierarchy with `BlockKey`, `BlobKey`, `ViewKey`, and
  `FileKey` variants.
- **Deterministic view scopes.** Represent `ViewKey.scope` as a
  `ListMap[String, DynamicValue]` so that scopes stay ordered and
  schema-agnostic.
- **Canonical ranges.** Standardise every I/O surface on the existing
  `ByteRange` type (usually wrapped in an `Option`) instead of ad-hoc tuples.
- **zio-blocks integration.** Bring `zio-blocks` in as a submodule to supply
  framing, Merkle, and helper utilities across projects.
- **Pure object store algebras.** Split `ObjectStore` into `Immutable` and
  `Mutable` traits that operate entirely in `IO`/`ZStream` without
  side-channel throws.

### Repository Layout

```
modules/
  core/         // refined types, binary keys, ranges, manifests, schema derivations
  streams/      // chunking, scans, time-series utilities (pure streaming logic)
  runtime/      // effectful storage algebras (BlobStore, BlockStore, KV, metrics glue)
  protocol/     // graviton-http (zio-http) and graviton-grpc (zio-grpc) front-doors
  backends/     // filesystem, S3, Postgres KV, RocksDB cache, Tika integration
  metrics/      // Prometheus adapters sitting on top of runtime algebras
  server/       // application wiring, http+grpc, health endpoints, shard orchestration
  zio-blocks/   // git submodule providing framing/Merkle helpers
docs/           // mdoc sources (optionally skinned via VitePress)
```

The `core` module remains pure and depends only on `zio-schema`, `iron`, and
`zio-blocks` (where helpers are required).  `runtime` composes algebras without
providing concrete drivers, while `backends` house the filesystem, S3, and
Postgres implementations.  Server wiring, metrics adapters, and protocol
modules depend on these layers but never on implementation details directly.

### Delivery Steps

1. **Add `zio-blocks` submodule.** Bring in `AdrielC/zio-blocks` under
   `modules/zio-blocks` and wire an `ProjectRef` into `build.sbt`.
2. **Define refined aliases.** Introduce canonical types such as
   `Size = Long :| Positive`, `Offset = Long :| NonNegative`, and
   `HexLower = String :| Matches[...]`, surfacing `mk*` constructors that
   return `Either`.
3. **Consolidate keys.** Create `BinaryKey.scala` inside `modules/core` with
   the sealed trait and variants.  Update call sites across runtime, protocol,
   and backends.
4. **Standardise ranges.** Replace tuple-based ranges in stores, backends, and
   protocols with `ByteRange`.
5. **Split object store traits.** Keep the pure `Immutable`/`Mutable` API in
   `core/objectstore`, using refined types and `ByteRange` everywhere.
6. **Update build graph.** Ensure `core`, `streams`, and `runtime` depend on
   `zio-blocks` where framing or Merkle helpers are required.  Allow specific
   backends to opt into the dependency as needed.
7. **Propagate no-throw guarantees.** Replace `require`, `throw`, and
   side-effecting constructors with `Either`/`ZIO`-based error channels.
8. **Finish protocol split.** Keep REST endpoints in `graviton-http` and
   gRPC services in `graviton-grpc`, each using the refined keys and
   `ByteRange` parameters.
9. **Server orchestration.** Wire Shardcake-based upload sessions, health,
   and metrics endpoints in `graviton-server` using the refined algebras.
10. **Regression guardrails.** Run `TESTCONTAINERS=0 ./sbt scalafmtAll test`
    before merges; extend integration tests to cover the refined key and
    range handling.

## Beyond 0.1

- Expand view tooling with format-aware helpers that operate on
  `DynamicValue` scopes.
- Prototype CDC-aware repair jobs and replica reconciliation using the range
  algebra retained in `modules/core/ranges`.
- Layer optional SDKs and CLI ergonomics atop the refined core types.
- Grow backend support (additional object stores, cold storage tiers) once the
  sealed key hierarchy and policy layer have settled.
