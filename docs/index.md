---
layout: home

hero:
  name: "Graviton"
  text: "Operational Content-Addressed Storage"
  tagline: "Persist bytes, inspect manifests, verify content, and retrieve it after restart"
  image:
    src: /logo.svg
    alt: Graviton Logo
  actions:
    - theme: brand
      text: Quickstart
      link: guide/getting-started
    - theme: alt
      text: Run Graviton
      link: guide/run-locally
    - theme: alt
      text: Open CAS Playground
      link: cas-playground
    - theme: alt
      text: GitHub
      link: https://github.com/AdrielC/graviton

features:
  - title: Restart-safe filesystem CAS
    details: Blocks and versioned manifests survive fresh CLI and server processes.
  - title: Real HTTP lifecycle
    details: Versioned upload, pagination, inspection, verification, ranges, preconditions, download, HEAD, and delete operate on the configured store.
  - title: Streaming runtime
    details: ZIO Streams bound ingestion and retrieval without buffering complete payloads.
  - title: External backend proof
    details: CI exercises S3-compatible blocks with PostgreSQL manifests using MinIO and Postgres.
  - title: Production controls
    details: OIDC, capabilities, request limits, audit chains, readiness, backup drills, reversible GC, SBOMs, and attestations are executable.
---

## Run the complete lifecycle

```bash
./scripts/verify-local-lifecycle.sh
```

The verification script ingests a file, starts fresh CLI JVMs for metadata, retrieval, and verification, and compares the retrieved bytes with the original. It prints the stable content ID and filesystem root for inspection.

For the HTTP service:

```bash
GRAVITON_BLOB_BACKEND=fs \
GRAVITON_FS_ROOT=/tmp/graviton-data \
GRAVITON_HTTP_PORT=8081 \
./sbt "server/run"
```

Then open [Connect Your Server](./demo.md). The console does not contain a sample dataset or a browser simulation. If it cannot reach the configured server, it shows the connection failure.

## Operational proof

Select a capability to inspect the repository evidence and executable command behind it.

<EvidenceHud />

## Interactive content lab

The public site has no hosted Graviton backend. This worksheet links `graviton-shared` for Scala.js and performs real UTF-8 encoding, fixed chunking, Web Crypto SHA-256, content-ID formatting, and repeated-block detection within a refined 8 KiB boundary.

<PipelinePlayground />

[Open the full CAS Playground](./cas-playground.md) for guided experiments and runtime boundaries.

## What is operational

| Path | Proof |
| --- | --- |
| Filesystem blocks and manifests | Restart-safe round-trip and inventory tests in `FsBlobManifestRepoSpec` |
| CLI ingest, stat, get, verify, and delete | `scripts/verify-local-lifecycle.sh` uses separate JVM invocations |
| HTTP v1 lifecycle, pagination, ranges, preconditions, and deprecated aliases | Contract coverage in `HttpApiSpec` |
| OIDC, capabilities, request controls, and chained audit | Security and packaged-server suites |
| Browser operations console | Compiled Scala.js client calls the same HTTP routes directly |
| Shared CAS content lab | The same bounded analyzer and contract tests run on JVM and Scala.js with native JCA/Web Crypto hashing |
| S3-compatible blocks and PostgreSQL manifests | Container-backed integration suites in CI |
| Quorum replication, fallback, and repair | `ReplicatedBlockStoreSpec` |
| Conservative block collection and restore | `GarbageCollectorSpec` plus S3 quarantine integration |
| RocksDB key-value adapter | Close and reopen persistence test |

## Storage flow

<div class="storage-flow" role="group" aria-label="Graviton storage flow from client bytes through retrieval and verification">
  <div class="storage-flow__stage">
    <span>01 · INPUT</span>
    <strong>Client bytes</strong>
    <small>Bounded request body</small>
  </div>
  <span class="storage-flow__arrow" aria-hidden="true">→</span>
  <div class="storage-flow__stage">
    <span>02 · INGEST</span>
    <strong>HTTP / CLI</strong>
    <small>Stream, chunk, hash</small>
  </div>
  <span class="storage-flow__arrow" aria-hidden="true">→</span>
  <div class="storage-flow__stage storage-flow__stage--split">
    <span>03 · DURABLE CAS</span>
    <strong>Blocks + manifest</strong>
    <small>Filesystem or S3 · Filesystem or PostgreSQL</small>
  </div>
  <span class="storage-flow__arrow" aria-hidden="true">→</span>
  <div class="storage-flow__stage">
    <span>04 · READ</span>
    <strong>Retrieve + verify</strong>
    <small>Rehash persisted bytes</small>
  </div>
</div>

## Explicit limits

Graviton is pre-1.0. The RocksDB module deliberately provides typed durable key-value storage rather than claiming to be a blob backend. Resumable HTTP uploads and automatic replica placement/repair scheduling are not wired into `Main`. Single-node filesystem mode is not HA. Shared S3 plus PostgreSQL needs target-environment concurrent-process and rolling-upgrade qualification. Published benchmark envelopes require retained representative samples.

## Continue

<div class="grid-container">
  <a href="guide/getting-started" class="feature-card">
    <h3>Quickstart</h3>
    <p>Add the library, choose a storage profile, and run your first CAS lifecycle.</p>
  </a>
  <a href="guide/run-locally" class="feature-card">
    <h3>Run locally</h3>
    <p>Start the filesystem server and exercise every live endpoint.</p>
  </a>
  <a href="cas-playground" class="feature-card">
    <h3>CAS Playground</h3>
    <p>Hash and chunk your own bytes locally with real SHA-256 content IDs.</p>
  </a>
  <a href="pipeline-explorer" class="feature-card">
    <h3>Pipeline Explorer</h3>
    <p>Map the interactive byte flow to Graviton's executable transducers.</p>
  </a>
  <a href="demo" class="feature-card">
    <h3>Connect your server</h3>
    <p>Operate a Graviton HTTP endpoint that you provide.</p>
  </a>
  <a href="api/http" class="feature-card">
    <h3>HTTP contract</h3>
    <p>Use the operational blob API from curl or another client.</p>
  </a>
  <a href="architecture" class="feature-card">
    <h3>Architecture</h3>
    <p>Understand storage, streaming, and backend boundaries.</p>
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
  transition: border-color 0.2s ease, transform 0.2s ease;
  text-decoration: none !important;
}

.feature-card:hover {
  border-color: var(--vp-c-brand-1);
  transform: translateY(-2px);
}

.feature-card h3 {
  color: var(--vp-c-brand-1);
  margin-top: 0;
}

.storage-flow {
  display: flex;
  align-items: stretch;
  gap: 0.65rem;
  margin: 1.5rem 0 2rem;
  overflow-x: auto;
  padding: 0.25rem 0.1rem 0.75rem;
}

.storage-flow__stage {
  display: flex;
  flex: 1 0 175px;
  flex-direction: column;
  gap: 0.35rem;
  justify-content: center;
  min-height: 120px;
  padding: 1rem;
  border: 1px solid color-mix(in srgb, var(--vp-c-brand-1) 60%, transparent);
  border-radius: 12px;
  background: color-mix(in srgb, var(--vp-c-bg-soft) 92%, var(--vp-c-brand-1));
}

.storage-flow__stage span {
  color: var(--vp-c-brand-1);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.storage-flow__stage strong {
  color: var(--vp-c-text-1);
  font-size: 1rem;
}

.storage-flow__stage small {
  color: var(--vp-c-text-2);
  line-height: 1.45;
}

.storage-flow__stage--split {
  border-color: var(--vp-c-brand-1);
  box-shadow: inset 0 0 24px color-mix(in srgb, var(--vp-c-brand-1) 8%, transparent);
}

.storage-flow__arrow {
  align-self: center;
  color: var(--vp-c-brand-1);
  flex: 0 0 auto;
  font-family: var(--vp-font-family-mono);
  font-size: 1.25rem;
}

@media (max-width: 700px) {
  .storage-flow {
    align-items: stretch;
    flex-direction: column;
    overflow-x: visible;
  }

  .storage-flow__stage {
    flex-basis: auto;
    min-height: 0;
  }

  .storage-flow__arrow {
    transform: rotate(90deg);
  }
}
</style>
