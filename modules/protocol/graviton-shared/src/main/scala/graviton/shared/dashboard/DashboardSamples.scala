package graviton.shared.dashboard

import graviton.shared.ApiModels.*
import zio.schema.DeriveSchema

/** Centralized fixtures for the datalake dashboard so JVM + JS stay in sync. */
object DashboardSamples {

  private val schemaAstJson: String =
    DeriveSchema.gen[DatalakeDashboard].ast.toString

  /** Reference metaschema describing the dashboard payload (generated via zio-schema AST). */
  val metaschema: DatalakeMetaschema =
    DatalakeMetaschema(
      format = "zio-schema-ast@1",
      astJson = schemaAstJson,
    )

  /** Reference snapshot mirroring the docs/ops/datalake-dashboard.md content. */
  val snapshot: DatalakeDashboard = DatalakeDashboard(
    lastUpdated = "2025-11-28",
    branch = "cursor/datalake-recent-changes-dashboard-gpt-5.1-codex-78c7",
    pillars = List(
      DatalakePillar(
        title = "Baseline health",
        status = "✅ Green",
        evidence = "Phase 0 log shows clean TESTCONTAINERS=0 ./sbt scalafmtAll test + npm run docs:build",
        impact = "Guarantees reproducible CI baseline before new ingest work.",
      ),
      DatalakePillar(
        title = "Type & attribute foundations",
        status = "🚧 In progress",
        evidence = "Phase 1 added Iron ByteConstraints, Block wrappers, and specs.",
        impact = "Enforces per-block limits before BlockStore/BlobStore integration.",
      ),
      DatalakePillar(
        title = "Reliability & observability",
        status = "✅ Hardened",
        evidence = "CHANGES_SUMMARY.md calls out JVM caps, forked suites, digest fixes.",
        impact = "Long ingest runs no longer OOM; 118/118 tests pass reliably.",
      ),
      DatalakePillar(
        title = "Experience & surfacing",
        status = "✅ Live",
        evidence = "FINAL_STATUS.md documents the Laminar dashboard and CI wiring.",
        impact = "Stakeholders can demo Stats, Blob Explorer, and Health checks directly.",
      ),
    ),
    highlights = List(
      DatalakeHighlight(
        category = "Ingest & Type Safety",
        bullets = List(
          "Restored Byte/Chunk refinements and manifest-safe BlockBuilder helpers.",
          "Added ByteConstraintsSpec + BlockSpec to guard size regressions.",
          "Extended docs/ingest/chunking.md with opaque wrapper guidance.",
        ),
      ),
      DatalakeHighlight(
        category = "Runtime Reliability & Tests",
        bullets = List(
          "Locked JVM heap (2 GiB / 512 MiB) with G1 + per-suite forks.",
          "Trimmed property sample counts + payload sizes for realistic stress.",
          "Patched digest handling so SHA-256 + BLAKE3 verification stops leaking buffers.",
        ),
      ),
      DatalakeHighlight(
        category = "Experience & Insight Surfaces",
        bullets = List(
          "Scala.js frontend ships Stats Panel, Blob Explorer, and Health Check.",
          "Matrix neon docs theme keeps Guide/Architecture/API/Scala.js/Demo prominent.",
          "DOCUMENTATION_STATUS.md details build + deployment commands for previewing the dashboard.",
        ),
      ),
    ),
    changeStream = List(
      DatalakeChangeEntry(
        date = "2025-11-06",
        area = "Tooling baseline",
        update = "Phase 0 confirmed clean repo, sbt scalafmtAll test, npm docs build.",
        impact = "Future datalake patches start from a reproducible baseline.",
        source = "docs/logs/2025-11-06.md",
      ),
      DatalakeChangeEntry(
        date = "2025-11-06",
        area = "Ingest types",
        update = "Phase 1 shipped Iron ByteConstraints, opaque Block/UploadChunk, updated docs.",
        impact = "Block safety enforced before hitting storage adapters.",
        source = "docs/logs/2025-11-06.md",
      ),
      DatalakeChangeEntry(
        date = "2025-10-30",
        area = "Docs infrastructure",
        update = "Verified sbt + VitePress builds and documented commands/endpoints.",
        impact = "Contributors can reliably regenerate the dashboard + docs.",
        source = "DOCUMENTATION_STATUS.md",
      ),
      DatalakeChangeEntry(
        date = "—",
        area = "Reliability fixes",
        update = "Introduced JVM tuning, digest leak fix, forked suites, neon docs theme.",
        impact = "118/118 suites pass without OOM; ingest simulations stay healthy.",
        source = "CHANGES_SUMMARY.md",
      ),
      DatalakeChangeEntry(
        date = "—",
        area = "Experience layer",
        update = "Delivered Laminar/Airstream dashboard modules + CI wiring for GitHub Pages.",
        impact = "Live cockpit (Stats, Blob Explorer, Health) tied to docs deployments.",
        source = "FINAL_STATUS.md",
      ),
    ),
    healthChecks = List(
      DatalakeHealthCheck(
        label = "Format + unit/integration suites",
        command = "TESTCONTAINERS=0 ./sbt scalafmtAll test",
        expectation = "All suites green (~75s) with bounded heap.",
      ),
      DatalakeHealthCheck(
        label = "Docs site",
        command = "cd docs && npm install && npm run docs:build",
        expectation = "Build succeeds without dead-link warnings.",
      ),
      DatalakeHealthCheck(
        label = "Frontend artifacts",
        command = "./sbt buildFrontend && cd docs && npm run docs:dev",
        expectation = "Scala.js bundle compiles; /demo renders dashboard.",
      ),
    ),
    operationalConfidence = List(
      DatalakeOperationalNote(
        label = "Metrics Surface",
        description = "Stats Panel reveals blob counts, storage usage, dedupe ratio, and health badges.",
      ),
      DatalakeOperationalNote(
        label = "Docs Accuracy",
        description = "Schema, chunking, ranges, and ingest guides kept in sync via DOCUMENTATION_STATUS.md.",
      ),
      DatalakeOperationalNote(
        label = "Navigation",
        description = "Guide → Streaming → Architecture → API → Scala.js → Demo remain one click away via VitePress config.",
      ),
    ),
    upcomingFocus = List(
      "Finish Phase 1 helper docs + BinaryAttribute interop wiring.",
      "Kick off Phase 2 FastCDC + rolling hash chunker extraction.",
      "Draft Phase 3 mime sniffer design so ingest metrics surface hints.",
      "Sketch Phase 7 mdoc plan to keep dashboard snippets executable.",
    ),
    sources = List(
      DatalakeSourceLink("docs/logs/2025-11-06.md", "docs/logs/2025-11-06.md"),
      DatalakeSourceLink("CHANGES_SUMMARY.md", "CHANGES_SUMMARY.md"),
      DatalakeSourceLink("DOCUMENTATION_STATUS.md", "DOCUMENTATION_STATUS.md"),
      DatalakeSourceLink("FINAL_STATUS.md", "FINAL_STATUS.md"),
    ),
  )
}
