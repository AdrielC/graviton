---
layout: home

hero:
  name: "⚡ Graviton"
  text: "Content-Addressable Storage"
  tagline: "Built on ZIO • Modular • Blazingly Fast"
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
    title: Content-Addressable
    details: Deduplicate at scale with content-defined chunking and cryptographic hashing. Store once, reference forever.
  - icon: ⚡
    title: Blazingly Fast
    details: Built on ZIO for maximum concurrency and efficiency. Stream terabytes without breaking a sweat.
  - icon: 🔧
    title: Modular Design
    details: Pluggable backends (S3, PostgreSQL, RocksDB), flexible protocols (gRPC, HTTP), and independent evolution.
  - icon: 🔐
    title: Type-Safe
    details: Leverages Scala 3, ZIO, and refined types for compile-time guarantees. If it compiles, it works.
  - icon: 📊
    title: Observable
    details: Prometheus metrics, structured logging, and correlation IDs built-in. Know what's happening in production.
  - icon: 🌐
    title: Production-Ready
    details: Replication, backpressure, graceful degradation, and zero-downtime deployments out of the box.
---

## 🛰️ Hyperdrive Telemetry

Get a taste of the Graviton control room. The live HUD keeps a pulse on ingest throughput, deduplication ratios, replication health, and the latest runtime chatter—rendered in neon, constantly shifting, and entirely in-browser.

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

Graviton is a **content-addressable storage runtime** that provides a stable ingest and retrieval pipeline for large binary payloads. The system is **modular by design** so that hashing, chunking, persistence, replication, and protocol concerns evolve independently.

::: info Visualize the pipelines
Every architecture and operations page now ships with live Mermaid diagrams. No extra setup required—VitePress renders them client-side with our neon skins so you can trace ingest, replication, and backend selection at a glance.
:::

### Key Features

- **Zero-Copy Streaming**: Process terabytes without loading into memory
- **Content-Defined Chunking**: FastCDC for optimal deduplication
- **Multiple Backends**: S3, PostgreSQL, RocksDB, and more
- **Type-Safe Schema**: Compile-time guarantees with ZIO Schema
- **Observable**: Prometheus metrics and structured logging
- **Replicated**: Multi-region support with eventual consistency
- **Schema Insights**: Interactive schema explorer powered by Scala.js + ZIO on the docs site

## 📚 Next Steps

:::tip 🎯 New to Graviton?
Start with the [Getting Started Guide](/guide/getting-started) for a hands-on introduction!
:::

## ✨ Launch the Quantum Command Center

Need warp-speed navigation? Hit `⌘K` / `Ctrl+K` (or click the floating button) to summon the Quantum Command Center—an omnipresent command palette with synthwave vibes, hyperspace unlocks, and instant jumps to the hottest docs.

<QuantumConsole />

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
