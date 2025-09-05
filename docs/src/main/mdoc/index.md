# Graviton

Graviton is a ZIO‑native port of the Binny binary storage layer. It provides
content‑addressable storage, block deduplication, and streaming APIs for
Quasar and other applications.

## Getting Started

- [Installation](getting-started/installation.md)
- [Quick Start](getting-started/quick-start.md)

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

## CAS Layer

* [CAS Layer Spec](cas-layer-spec.md)
* [Roadmap](roadmap.md)
