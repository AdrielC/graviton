<template>
  <main class="graviton-homepage">
    <section class="graviton-home-hero" aria-labelledby="graviton-home-title">
      <div class="graviton-home-hero__copy">
        <img
          class="graviton-home-hero__mark"
          :src="withBase('/logo.svg')"
          alt=""
          width="96"
          height="96"
          fetchpriority="high"
        />
        <h1 id="graviton-home-title">Store bytes by what they are.</h1>
        <p>
          Graviton streams files into immutable, content-addressed blocks. Durable manifests put the exact
          bytes back together, while repeated blocks are stored once.
        </p>
        <div class="graviton-home-actions">
          <a class="graviton-action graviton-action--primary" :href="withBase('/guide/getting-started')">
            Start with Graviton
          </a>
          <a class="graviton-action" :href="withBase('/architecture')">Read the architecture</a>
        </div>
        <ul class="graviton-home-facts" aria-label="Graviton foundations">
          <li>Scala 3</li>
          <li>ZIO Streams</li>
          <li>Filesystem or S3</li>
          <li>PostgreSQL manifests</li>
        </ul>
      </div>

      <aside class="graviton-quick-run" aria-labelledby="graviton-quick-run-title">
        <header>
          <h2 id="graviton-quick-run-title">Run a durable local store</h2>
          <span>filesystem</span>
        </header>
        <pre aria-label="Commands to run Graviton locally"><code>{{ quickRunCommand }}</code></pre>
        <p>Upload, download, organize, and inspect real deduplication through the built-in DataStar console.</p>
        <a :href="withBase('/guide/run-locally')">
          Run the complete lifecycle <span aria-hidden="true">→</span>
        </a>
      </aside>
    </section>

    <nav class="graviton-start-paths" aria-label="Start with Graviton">
      <a :href="withBase('/guide/scala-sdk')">
        <strong>Embed the Scala API</strong>
        <span>Stream files through a typed ZIO service.</span>
        <b aria-hidden="true">→</b>
      </a>
      <a :href="withBase('/guide/run-locally')">
        <strong>Run the server</strong>
        <span>Use HTTP, gRPC, CLI, or the local console.</span>
        <b aria-hidden="true">→</b>
      </a>
      <a :href="withBase('/ops/deployment')">
        <strong>Deploy shared storage</strong>
        <span>Compose S3 blocks with PostgreSQL manifests.</span>
        <b aria-hidden="true">→</b>
      </a>
    </nav>

    <section class="graviton-home-section graviton-byte-path" aria-labelledby="graviton-byte-path-title">
      <header>
        <h2 id="graviton-byte-path-title">One identity across every backend</h2>
        <p>
          Storage adapters can change without changing what a blob means. Content identity is derived from
          bytes, not filenames, locations, or upload sessions.
        </p>
      </header>
      <ol>
        <li>
          <span>Stream</span>
          <strong>Read bounded chunks</strong>
          <p>Uploads stay backpressured without collecting the complete payload.</p>
        </li>
        <li>
          <span>Address</span>
          <strong>Hash each block</strong>
          <p>Existing block keys turn repeated content into reuse.</p>
        </li>
        <li>
          <span>Commit</span>
          <strong>Publish the manifest</strong>
          <p>Ordered block references make the blob durable and retrievable.</p>
        </li>
        <li>
          <span>Verify</span>
          <strong>Rehash stored bytes</strong>
          <p>Integrity checks read the persisted content instead of trusting metadata.</p>
        </li>
      </ol>
    </section>

    <section class="graviton-home-section graviton-proof" aria-labelledby="graviton-proof-title">
      <header>
        <h2 id="graviton-proof-title">What the repository proves</h2>
        <p>Every row points to an executable path or an integration-tested boundary.</p>
      </header>
      <div class="graviton-proof__rows">
        <a :href="withBase('/guide/run-locally')">
          <strong>Restart-safe local CAS</strong>
          <span>Separate CLI and server processes ingest, retrieve, rehash, and compare bytes.</span>
          <code>./scripts/verify-local-lifecycle.sh</code>
        </a>
        <a :href="withBase('/api/http')">
          <strong>Streaming HTTP and gRPC</strong>
          <span>Upload, list, inspect, verify, range-read, download, and delete through packaged listeners.</span>
          <code>./scripts/smoke-packaged-server.sh</code>
        </a>
        <a :href="withBase('/guide/storage-backends')">
          <strong>Shared storage adapters</strong>
          <span>CI runs PostgreSQL manifests and S3-compatible blocks against PostgreSQL and MinIO.</span>
          <code>GRAVITON_IT=1 ./sbt test</code>
        </a>
        <a :href="withBase('/ops/production-readiness')">
          <strong>Operational controls</strong>
          <span>Readiness, authorization, audit, backup, restore, and reversible collection have explicit gates.</span>
          <code>docs/ops/production-readiness.md</code>
        </a>
      </div>
      <aside class="graviton-boundary-note">
        <strong>Deployment boundary</strong>
        <p>
          Graviton is pre-1.0. Single-node filesystem mode is operational. Shared S3 and PostgreSQL deployments
          still require workload, failover, and rolling-upgrade qualification in their target environment.
        </p>
      </aside>
    </section>

    <section class="graviton-home-section graviton-home-lab" aria-labelledby="graviton-home-lab-title">
      <header>
        <h2 id="graviton-home-lab-title">Change the bytes. Watch identity change.</h2>
        <p>
          This bounded browser lab runs the shared Scala.js analyzer and Web Crypto locally. It demonstrates
          content identity and block reuse without pretending to persist anything.
        </p>
      </header>
      <PipelinePlayground />
      <a class="graviton-inline-link" :href="withBase('/cas-playground')">
        Open the full CAS Playground <span aria-hidden="true">→</span>
      </a>
    </section>

    <section class="graviton-home-finish" aria-labelledby="graviton-home-finish-title">
      <h2 id="graviton-home-finish-title">Go from bytes to a verified blob.</h2>
      <div>
        <a class="graviton-action graviton-action--primary" :href="withBase('/guide/getting-started')">
          Follow the Quickstart
        </a>
        <a class="graviton-action" href="https://github.com/AdrielC/graviton">View the source</a>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { withBase } from 'vitepress'

const quickRunCommand = `GRAVITON_CONSOLE_ENABLED=true \\
  ./sbt "server/run"

open http://127.0.0.1:8081/console`
</script>
