---
id: index
title: "Getting Started with Graviton"
sidebar_label: "Getting Started"
---

@PROJECT_BADGES@

Graviton is a ZIO‑native content‑addressable storage layer for immutable binary data. It ingests byte streams, deduplicates blocks, replicates them across backends, and streams them back with integrity guarantees so applications can build higher‑level document workflows.

## Getting Started

- [Installation](getting-started/installation.md)
- [Quick Start](getting-started/quick-start.md)

## Core Concepts

- [Graviton Glossary](concepts.md) – canonical definitions and invariants for blocks, manifests, blobs, and replicas.
- [Architecture](architecture.md) – system overview and interactions between chunkers, stores, and catalogs.
- [Storage API overview](storage-api-overview.md) – deep dive into streaming ingest, manifests, and frame formats.
- [Binary store walkthrough](binary-store.md) – how public APIs map onto the underlying CAS layer.

## Modules and Guides

* **core** – base types and in‑memory stores used in tests and examples.
* **fs** – filesystem backed blob store.
* **s3** – S3‑compatible blob store usable with AWS or MinIO.
* **tika** – media type detection utilities backed by Apache Tika.
* **metrics** – Prometheus instrumentation for core operations.
* [Logging](logging.md) – structured logging with correlation IDs and ingest tracing.
* [Scan utilities](scan.md) – composable streaming transformers.
* [Examples](examples/index.md) – CLI and HTTP gateway walkthroughs.

## Further Reading

* [Design goals](design-goals.md) – overview of implemented features and active work.
* [Use cases](use-cases.md) – real‑world scenarios Graviton is built to support.
* [Chunking strategies](chunking.md) – guidance on tuning FastCDC and anchored chunkers.
* [Roadmap](roadmap.md) – broader program milestones and upcoming improvements.
