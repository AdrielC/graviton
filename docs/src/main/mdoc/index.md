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
