# Roadmap

## 0.1 Foundations

- Adopt Iron refined opaque types across the pure `core` module and expose
  `refineEither` smart constructors for all external inputs.
- Collapse binary identifiers under the sealed `BinaryKey` hierarchy with
  deterministic `ViewKey` scopes (`ListMap[String, DynamicValue]`).
- Replace ad-hoc range tuples with the existing `ByteRange` type in every I/O
  API.
- Split the pure object store API into `Immutable` and `Mutable` traits with
  refined types and effectful error channels.
- Bring in the `zio-blocks` git submodule and wire its project into
  `build.sbt` for Merkle/framing helpers.
- Restructure modules as:
  - `core`, `streams`, and `runtime` for pure types, streaming utilities, and
    effectful algebras respectively.
  - `protocol/` for `graviton-http` and `graviton-grpc` front-doors.
  - `backends/` for filesystem, S3, Postgres KV, RocksDB cache, and Tika.
  - `server/` for application wiring and Shardcake-backed upload orchestration.
  - `metrics/` for Prometheus adapters layered on runtime algebras.
- Maintain no-throw guarantees across boundaries, returning `Either`/`ZIO`
  errors instead of throwing exceptions.
- Run `TESTCONTAINERS=0 ./sbt scalafmtAll test` before merging.

## Beyond 0.1

- Expand view tooling and scope-aware helpers layered on `DynamicValue`.
- Build replica repair and range-planning jobs that reuse the retained range
  algebra in `modules/core/ranges`.
- Add optional SDKs, CLI enhancements, and additional backends once the refined
  layout stabilises.
