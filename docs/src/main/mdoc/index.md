# Graviton

Graviton is a ZIO‑native port of the Binny binary storage layer. It provides
content‑addressable storage, block deduplication, and streaming APIs for
Quasar and other applications.

## Current status

- Implemented: content‑addressable storage (BLAKE3), layered block/file model, ZIO Streams APIs, filesystem and S3 blob stores, media type detection (Tika), structured logging, Prometheus metrics, scan utilities.
- Modules: `core`, `fs`, `s3`, `tika`, `metrics`, `pg`.
- Examples: CLI usage and HTTP gateway.
- Planned: view materialization improvements, replication/healing controls, production ops guides.

## Modules

* **core** – base types and in‑memory stores used in tests and examples.
* **fs** – filesystem backed blob store.
* **s3** – S3‑compatible blob store usable with AWS or MinIO.
* **tika** – media type detection utilities backed by Apache Tika.
* **metrics** – Prometheus instrumentation for core operations.
* [Logging](logging.md) – structured logging with correlation IDs.
* [Scan utilities](scan.md) – composable streaming transformers.

See [architecture.md](architecture.md) for a high‑level overview of the storage
stack.

Additional details about the layered model and terminology live in
[binary-store.md](binary-store.md).

See the [examples](examples/index.md) for end‑to‑end CLI and HTTP gateway
walkthroughs.

## Quick links

- Architecture: [architecture.md](architecture.md), [binary-store.md](binary-store.md)
- Operations: [logging.md](logging.md), [metrics.md](metrics.md)
- Utilities: [scan.md](scan.md), [file-descriptor-schema.md](file-descriptor-schema.md)
- Examples: [examples/index.md](examples/index.md)
