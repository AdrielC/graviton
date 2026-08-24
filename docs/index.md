---
layout: home

hero:
  name: "Graviton"
  text: "Content-Addressable Storage Runtime"
  tagline: "Built on ZIO • Streaming ingest • Backend agnostic"
  image:
    src: /logo.svg
    alt: Graviton Logo
  actions:
    - theme: brand
      text: Get Started
      link: guide/getting-started
    - theme: alt
      text: Architecture
      link: architecture
    - theme: alt
      text: GitHub
      link: https://github.com/AdrielC/graviton

features:
  - title: Verified Content Keys
    details: Bounded chunking and cryptographic hashing ensure every stored blob is addressed by its bytes.
  - title: Composable Transducer Pipelines
    details: The Transducer algebra lets you compose ingest stages with >>> and &&&, with typed summaries, automatic state merging, and zero boilerplate.
  - title: Stream-First Runtime
    details: ZIO Streams power ingestion, hashing, block persistence, and retrieval without buffering complete payloads.
  - title: Modular Backends
    details: Run the restart-safe filesystem composition with no external services, or select S3/MinIO blocks with PostgreSQL manifests.
  - title: Strong Typing
    details: Scala 3, refined types, and schema derivation guard invariants across transports and storage boundaries.
  - title: Built-In Observability
    details: Structured logging, in-process counters, and Prometheus text export surface runtime behavior.
---

## Operational Proof

Graviton does not need fictional throughput numbers to look capable. These claims map to executable tests, durable formats, and CI gates in the repository.

<NeonHud />

## Quick Start

```bash
# Ingest, restart the CLI, retrieve, verify, and compare bytes
./scripts/demo-local.sh
```

The script prints a stable blob ID in the form `sha-256:<digest>:<size>` and leaves its temporary store path available for inspection.

## Why Graviton?

Graviton is a **content-addressable storage runtime** that coordinates chunking, hashing, manifest persistence, and retrieval for large binary payloads. Each concern lives in an isolated module so hashing algorithms, network protocols, and storage backends can evolve independently.

::: info Visualize the pipelines
Architecture, manifests, and operations pages include interactive Mermaid diagrams rendered client-side in VitePress. Follow ingest, replication, and backend selection without leaving the browser.
:::

### Key Features

- **Composable Transducer pipelines**: build ingest, verify, and retrieval paths from typed stages with `>>>` and `&&&`
- **Streaming ingest and retrieval** with bounded ZIO Stream pipelines
- **Content-defined chunking** via FastCDC and multi-hash verification
- **Pluggable storage** with working filesystem and S3 block stores plus filesystem and PostgreSQL manifests
- **Strongly-typed schemas** shared across HTTP, gRPC, and Scala.js clients
- **Integrated observability** with Prometheus metrics and structured logging
- **Interactive Pipeline Explorer**: compose and visualize transducer stages in the browser

### Capability Status

| Path | Status | Proof |
| --- | --- | --- |
| Filesystem blocks + manifests | **Operational** | Restart-safe round-trip in `FsBlobManifestRepoSpec` |
| CLI ingest / stat / get / verify / delete | **Operational** | `scripts/demo-local.sh` exercises separate JVM invocations |
| HTTP POST / GET / HEAD / DELETE | **Operational, pre-1.0** | Contract coverage in `HttpApiSpec` |
| S3 blocks + PostgreSQL manifests | **Integration-tested** | Container-gated suites in CI |
| RocksDB | **Partial** | Durable key-value adapter works; CAS block-store wiring remains roadmap |
| Replica repair and placement | **Planned** | Runtime ports exist; coordinator is not complete |

## Pipeline Explorer

Compose transducer stages interactively. Toggle stages on and off, inspect the resulting expression, and run a deterministic browser visualization of the modeled pipeline.

<PipelinePlayground />

[Open full Pipeline Explorer](pipeline-explorer.md) for detailed explanations and scenarios.

## Next Steps

:::tip New to Graviton?
Start with the [Getting Started Guide](guide/getting-started.md) for a hands-on introduction!
:::

<div class="grid-container">
  <a href="cas-playground" class="feature-card">
    <h3>CAS Playground</h3>
    <p>Inspect a deterministic browser model of chunking, hashing, and deduplication</p>
  </a>

  <a href="pipeline-explorer" class="feature-card">
    <h3>Pipeline Explorer</h3>
    <p>Compose transducer stages and run a clearly labeled browser visualization</p>
  </a>

  <a href="architecture" class="feature-card">
    <h3>Architecture</h3>
    <p>Understand the module-by-module breakdown and system design</p>
  </a>
  
  <a href="end-to-end-upload" class="feature-card">
    <h3>Upload Flow</h3>
    <p>Follow a binary blob through the entire ingest pipeline</p>
  </a>
  
  <a href="core/transducers" class="feature-card">
    <h3>Transducer Algebra</h3>
    <p>Typed, composable pipeline stages with Record summaries</p>
  </a>
  
  <a href="api" class="feature-card">
    <h3>API Reference</h3>
    <p>Explore gRPC and HTTP endpoints with examples</p>
  </a>
  
  <a href="dev/contributing" class="feature-card">
    <h3>Contributing</h3>
    <p>Join the community and help build the future of storage</p>
  </a>
</div>

<style>
.grid-container {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 1.5rem;
  margin-top: 2rem;
}

.feature-card {
  padding: 1.5rem;
  border: 1px solid var(--vp-c-brand-soft);
  border-radius: 12px;
  background: rgba(0, 255, 65, 0.03);
  transition: all 0.3s ease;
  text-decoration: none !important;
}

.feature-card:hover {
  border-color: var(--vp-c-brand-1);
  background: rgba(0, 255, 65, 0.08);
  transform: translateY(-4px);
  box-shadow: 0 10px 30px rgba(0, 255, 65, 0.2);
}

.feature-card h3 {
  color: var(--vp-c-brand-1);
  margin-top: 0;
  margin-bottom: 0.5rem;
}

.feature-card p {
  color: var(--vp-c-text-2);
  margin: 0;
}
</style>
