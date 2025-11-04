---
layout: home

hero:
  name: "⚡ Graviton"
  text: "Content-Addressable Storage Runtime"
  tagline: "Built on ZIO • Streaming ingest • Backend agnostic"
  image:
    src: /logo.svg
    alt: Graviton Logo
  actions:
    - theme: brand
      text: 🚀 Get Started
      link: /guide/getting-started
    - theme: alt
      text: 📖 Architecture
      link: /architecture
    - theme: alt
      text: ⚡ GitHub
      link: https://github.com/AdrielC/graviton

features:
  - icon: 🎯
    title: Verified Content Keys
    details: Content-defined chunking and cryptographic hashing ensure every blob is stored and addressed by its bytes.
  - icon: 🔄
    title: Stream-First Runtime
    details: ZIO Streams power ingestion, hashing, and replication so large payloads flow without buffering.
  - icon: 🔧
    title: Modular Backends
    details: Swap persistence layers with S3, PostgreSQL, RocksDB, or new implementations without touching the core types.
  - icon: 🔐
    title: Strong Typing
    details: Scala 3, refined types, and schema derivation guard invariants across transports and storage boundaries.
  - icon: 📊
    title: Built-In Observability
    details: Structured logging, Prometheus metrics, and correlation IDs surface ingestion and retrieval behaviour.
  - icon: 🛠️
    title: Operations Ready
    details: Replication policies, backpressure, and failure recovery hooks keep clusters responsive under load.
---

## 📡 Operations Snapshot

Track ingest throughput, deduplication ratios, replica health, and runtime events directly in the docs. The live HUD mirrors the data surfaced by the runtime's Prometheus exporters so you can see what operators monitor day to day.

<NeonHud />

## 🎮 Quick Start

```bash
# Build all modules
sbt compile

# Build the Scala.js dashboard for the /demo page
./sbt buildFrontend

# Run the full test suite  
TESTCONTAINERS=0 ./sbt scalafmtAll test

# Launch this documentation site
cd docs && npm install && npm run docs:dev
```

## 🚀 Why Graviton?

Graviton is a **content-addressable storage runtime** that coordinates chunking, hashing, replication, and retrieval for large binary payloads. Each concern lives in an isolated module so hashing algorithms, network protocols, and storage backends can evolve independently.

::: info Visualize the pipelines
Architecture, manifests, and operations pages include interactive Mermaid diagrams rendered client-side in VitePress. Follow ingest, replication, and backend selection without leaving the browser.
:::

### Key Features

- **Streaming ingest and retrieval** with ZIO Streams and zero-copy pipelines
- **Content-defined chunking** via FastCDC and multi-hash verification
- **Pluggable storage backends** for S3, PostgreSQL, RocksDB, and future targets
- **Strongly-typed schemas** shared across HTTP, gRPC, and Scala.js clients
- **Integrated observability** with Prometheus metrics and structured logging
- **Replica coordination** through policies that balance durability and latency
- **Schema explorer** powered by the Scala.js dashboard embedded in the docs

## 📚 Next Steps

:::tip 🎯 New to Graviton?
Start with the [Getting Started Guide](/guide/getting-started) for a hands-on introduction!
:::

<div class="grid-container">
  <a href="/architecture" class="feature-card">
    <h3>🏗️ Architecture</h3>
    <p>Understand the module-by-module breakdown and system design</p>
  </a>
  
  <a href="/end-to-end-upload" class="feature-card">
    <h3>📤 Upload Flow</h3>
    <p>Follow a binary blob through the entire ingest pipeline</p>
  </a>
  
  <a href="/api" class="feature-card">
    <h3>🔌 API Reference</h3>
    <p>Explore gRPC and HTTP endpoints with examples</p>
  </a>
  
  <a href="/dev/contributing" class="feature-card">
    <h3>🤝 Contributing</h3>
    <p>Join the community and help build the future of storage</p>
  </a>

  <a href="/dev/scalajs" class="feature-card">
    <h3>🧪 Scala.js Playbook</h3>
    <p>Run the Laminar dashboard locally with live reload and shared models</p>
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
