# Graviton

Graviton is a ZIO‑native port of the Binny binary storage layer. It provides
content‑addressable storage, block deduplication, and streaming APIs for
Quasar and other applications.

## Modules

* **core** – base types and in‑memory stores used in tests and examples.
* **fs** – filesystem backed blob store.
* **minio** – S3‑compatible blob store using zio‑aws.
* **tika** – media type detection utilities backed by Apache Tika.
* **metrics** – Prometheus instrumentation for core operations.
* [Scan utilities](scan.md) – composable streaming transformers.

See [architecture.md](architecture.md) for a high‑level overview of the storage
stack.

Additional details about the layered model and terminology live in
[binary-store.md](binary-store.md).

See the [examples](examples/index.md) for end‑to‑end CLI and HTTP gateway
walkthroughs.

## Logging

Graviton relies on [ZIO Logging](https://github.com/zio/zio-logging) for
structured logs. Every high‑level operation logs its start, completion, and
failures. A correlation ID is attached to each request and propagated across
layers so log lines from a single request can be grouped together.

By default logs are written to the console at the `INFO` level. The level or
backend can be customized by supplying a different logging layer:

```scala
import graviton.Logging
import zio.logging.LogLevel

val logging = Logging.layer(LogLevel.Debug)
```
