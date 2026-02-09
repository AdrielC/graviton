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
    details: Content-defined chunking and cryptographic hashing ensure every blob is stored and addressed by its bytes.
  - title: Composable Transducer Pipelines
    details: The Transducer algebra lets you compose ingest stages with >>> and &&& — typed summaries, automatic state merging, zero boilerplate.
  - title: Stream-First Runtime
    details: ZIO Streams power ingestion, hashing, and replication so large payloads flow without buffering.
  - title: Modular Backends
    details: Start with in-memory stores, then move to filesystem or S3/MinIO block storage plus PostgreSQL metadata as your deployment matures.
  - title: Strong Typing
    details: Scala 3, refined types, and schema derivation guard invariants across transports and storage boundaries.
  - title: Built-In Observability
    details: Structured logging, Prometheus metrics, and correlation IDs surface ingestion and retrieval behaviour.
---

## Operations Snapshot

Track ingest throughput, deduplication ratios, replica health, and runtime events directly in the docs. The live HUD mirrors the data surfaced by the runtime's Prometheus exporters so you can see what operators monitor day to day.

<NeonHud />

## Quick Start

```bash
# Build all modules
./sbt compile

# Build the Scala.js dashboard for the /demo page
./sbt buildFrontend

# Run the full test suite  
TESTCONTAINERS=0 ./sbt scalafmtAll test

# Launch this documentation site
cd docs && npm install && npm run docs:dev
```

## Why Graviton?

Graviton is a **content-addressable storage runtime** that coordinates chunking, hashing, replication, and retrieval for large binary payloads. Each concern lives in an isolated module so hashing algorithms, network protocols, and storage backends can evolve independently.

::: info Visualize the pipelines
Architecture, manifests, and operations pages include interactive Mermaid diagrams rendered client-side in VitePress. Follow ingest, replication, and backend selection without leaving the browser.
:::

### Key Features

- **Composable Transducer pipelines** — build ingest, verify, and retrieval paths from typed stages with `>>>` and `&&&`
- **Streaming ingest and retrieval** with ZIO Streams and zero-copy pipelines
- **Content-defined chunking** via FastCDC and multi-hash verification
- **Pluggable storage backends** for S3, PostgreSQL, RocksDB, and future targets
- **Strongly-typed schemas** shared across HTTP, gRPC, and Scala.js clients
- **Integrated observability** with Prometheus metrics and structured logging
- **Replica coordination** through policies that balance durability and latency
- **Interactive Pipeline Explorer** — compose and visualize transducer stages in the browser

## Pipeline Explorer

Compose transducer stages interactively — toggle stages on and off, see the composition expression update in real time, and watch animated data flow through the pipeline.

<PipelinePlayground />

[Open full Pipeline Explorer](pipeline-explorer.md) for detailed explanations and scenarios.

## Next Steps

:::tip New to Graviton?
Start with the [Getting Started Guide](guide/getting-started.md) for a hands-on introduction!
:::

<div class="grid-container">
  <a href="pipeline-explorer" class="feature-card">
    <h3>Pipeline Explorer</h3>
    <p>Compose transducer stages interactively and watch data flow in real time</p>
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
