---
layout: home

hero:
  name: "Graviton"
  text: "Content-addressable storage runtime"
  tagline: "Build reliable ingest, replication, and retrieval pipelines on ZIO."
  image:
    src: /logo.svg
    alt: Graviton Logo
  actions:
    - theme: brand
      text: Get Started
      link: /guide/getting-started
    - theme: alt
      text: Architecture Overview
      link: /architecture
    - theme: alt
      text: View on GitHub
      link: https://github.com/AdrielC/graviton

features:
  - title: Ingest at scale
    details: Stream large binaries with content-defined chunking, zero-copy pipelines, and backpressure-aware IO.
  - title: Pluggable storage
    details: Configure filesystem, S3, or PostgreSQL targets without rewriting the ingest path.
  - title: Schema-first API
    details: Share the same contracts across gRPC, HTTP, and Scala.js clients.
  - title: Operational insight
    details: Metrics, tracing, and structured logging hooks are available throughout the runtime.
---

## What's inside

Graviton packages the primitives you need to ingest, catalogue, and retrieve large binary workloads. These docs cover the control-plane APIs, the runtime behavior of each ingest stage, and guidance for running the system in production environments.

## Build locally

```bash
# Build all modules
sbt compile

# Run the full test suite
TESTCONTAINERS=0 ./sbt scalafmtAll test

# Launch the documentation site
cd docs && npm install && npm run docs:dev
```

## Core capabilities

- **Content-addressable identifiers** ensure every blob and block is immutable and deduplicated by default.
- **Configurable chunking** lets you choose fixed sizing or FastCDC depending on latency and deduplication goals.
- **Extensible storage adapters** support local filesystems, PostgreSQL, S3, and custom backends by configuration.
- **Schema-driven manifests** catalogue blocks, attributes, and checksums for each ingest.
- **Operational guardrails** expose Prometheus metrics, structured logs, and health endpoints to keep clusters observable.

## Quick links

:::tip New to Graviton?
Start with the [Getting Started guide](/guide/getting-started) for a hands-on introduction.
:::

<div class="home-link-grid">
  <a href="/architecture" class="home-link-card">
    <h3>Architecture</h3>
    <p>How the ingest pipeline, runtime, and stores fit together.</p>
  </a>
  <a href="/end-to-end-upload" class="home-link-card">
    <h3>Ingest walkthrough</h3>
    <p>Follow a blob from chunking to manifest publication.</p>
  </a>
  <a href="/api" class="home-link-card">
    <h3>API reference</h3>
    <p>gRPC and HTTP contracts with examples and error semantics.</p>
  </a>
  <a href="/dev/contributing" class="home-link-card">
    <h3>Contributing guide</h3>
    <p>Project standards, formatting rules, and release checks.</p>
  </a>
  <a href="/dev/scalajs" class="home-link-card">
    <h3>Scala.js tooling</h3>
    <p>Build the Laminar dashboard and connect it to a running runtime.</p>
  </a>
</div>
