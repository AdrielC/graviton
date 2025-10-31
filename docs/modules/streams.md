# Streams Utilities

`graviton-streams` supplies reusable ZIO Stream components that power chunking, hashing, and framing inside the runtime and demo.

## Chunking & hashing

- `Chunker`: factory for chunking pipelines. The current implementation is a placeholder (`ZPipeline.identity`) and will be replaced with FastCDC-based splitters that honour min/avg/max window sizes.
- `HashingZ`: exposes `sink` and `pipeline` helpers for `Hasher`/`MultiHasher` instances from `graviton-core`. These run incremental updates across byte streams and surface either a final hash or the original hasher for chained computations.

## Stream combinators

- `StreamTools.teeTo` mirrors the behaviour of UNIX `tee`, forwarding elements to a `ZSink` while preserving the original stream. Use this to attach side-effecting observers (metrics, audit logs) without rewriting ingestion code.

## Timeseries helpers

The `timeseries` package contains small, immutable data structures used to summarise ingest behaviour:

- `Histogram`: bucket counts (currently 1 KiB bucket width) for chunk size or throughput measurements.
- `Rate`: exponential moving average to smooth instantaneous rates.
- `Windowed`: fixed-size rolling window for recent observations.

These types are pure and can be shared between JVM services and Scala.js visualisations.

## Scodec interop

`interop/scodec/ZStreamDecoder` bridges scodec decoders with ZIO streams. It offers four entry points:

- `once` / `many`: strict decoders that fail fast on errors.
- `tryOnce` / `tryMany`: lenient variants that swallow recoverable errors and continue streaming.

The implementation keeps an internal `BitVector` buffer, tracks `Err.InsufficientBits`, and emits decoded values as soon as a decoder consumes input. It is already production-ready and covered by unit tests in `modules/graviton-streams/src/test`.

## Roadmap

1. Implement FastCDC chunker boundaries and expose configuration knobs (min/avg/max, fingerprint polynomial).
2. Add `ZPipeline` stages for compression and encryption so block storage can pipe bytes through a single reusable composition.
3. Export additional metrics (histograms, gauges) derived from the `timeseries` helpers.
