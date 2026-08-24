package graviton.shared.dashboard

import graviton.shared.ApiModels.*
import graviton.shared.schema.SchemaExplorer
import zio.schema.DeriveSchema

/** Source-backed reference data for the optional dashboard UI. */
object DashboardSamples:

  private val dashboardSchema = DeriveSchema.gen[DatalakeDashboard]

  private val schemaAstJson: String =
    dashboardSchema.ast.toString

  /** Reference metaschema describing the dashboard payload. */
  val metaschema: DatalakeMetaschema =
    DatalakeMetaschema(
      format = "zio-schema-ast@1",
      astJson = schemaAstJson,
    )

  /** Normalized accessor graph derived from the public dashboard schema. */
  val schemaExplorer: SchemaExplorer.Graph =
    SchemaExplorer.describe(dashboardSchema)

  /**
   * Honest reference snapshot used before an operator publishes runtime data.
   *
   * Every claim points to a source file or executable check in this repository.
   * This value is intentionally static; it is not presented as live telemetry.
   */
  val snapshot: DatalakeDashboard = DatalakeDashboard(
    lastUpdated = "reference snapshot",
    branch = "repository",
    pillars = List(
      DatalakePillar(
        title = "Streaming CAS path",
        status = "Implemented",
        evidence = "CasRoundTripSpec exercises ingest, manifest persistence, retrieval, stats, and integrity.",
        impact = "The core object path is executable rather than architectural scaffolding.",
      ),
      DatalakePillar(
        title = "Durable local mode",
        status = "Implemented",
        evidence = "FsBlobManifestRepoSpec recreates the store between write and read.",
        impact = "CLI and embedded filesystem workflows survive process restarts.",
      ),
      DatalakePillar(
        title = "HTTP object API",
        status = "Implemented",
        evidence = "HttpApiSpec covers POST, GET, HEAD, DELETE, validation, and not-found behavior.",
        impact = "Clients receive stable content IDs and explicit HTTP semantics.",
      ),
      DatalakePillar(
        title = "Advanced backends",
        status = "Mixed",
        evidence = "Filesystem, S3 block storage, and Postgres manifests are implemented; the RocksDB KV adapter is not yet a CAS backend.",
        impact = "Capability tables distinguish working paths from extension points.",
      ),
    ),
    highlights = List(
      DatalakeHighlight(
        category = "Content integrity",
        bullets = List(
          "Blob and block keys encode hash algorithm, digest, and byte length.",
          "Framed manifests validate ordering, bounds, and entry structure during decode.",
          "Verification streams bytes through a fresh hasher without buffering the full blob.",
        ),
      ),
      DatalakeHighlight(
        category = "Operational behavior",
        bullets = List(
          "Filesystem blocks and manifests use temporary files plus atomic rename.",
          "HTTP retrieval checks manifest existence before returning a streaming 200 response.",
          "Metrics and structured logging are available without fabricated production values.",
        ),
      ),
      DatalakeHighlight(
        category = "Contributor experience",
        bullets = List(
          "The sbt build loads from standard Git linked worktrees.",
          "Required unit, docs-snippet, frontend, and VitePress checks are documented and automated.",
          "The public site separates implemented, partial, and planned capabilities.",
        ),
      ),
    ),
    changeStream = List(
      DatalakeChangeEntry(
        date = "current",
        area = "Local storage",
        update = "Added durable framed manifests for filesystem-backed Graviton instances.",
        impact = "Ingest, stat, get, verify, and delete work across separate processes.",
        source = "modules/graviton-runtime/src/main/scala/graviton/runtime/stores/FsBlobManifestRepo.scala",
      ),
      DatalakeChangeEntry(
        date = "current",
        area = "Protocol",
        update = "Hardened content ID parsing and blob HTTP lifecycle semantics.",
        impact = "Returned IDs round-trip and missing objects return 404 before body streaming starts.",
        source = "modules/protocol/graviton-http/src/main/scala/graviton/protocol/http/HttpApi.scala",
      ),
      DatalakeChangeEntry(
        date = "current",
        area = "Documentation",
        update = "Replaced simulated telemetry and stale status prose with source-backed proof.",
        impact = "The showcase remains credible without presenting random data as operational evidence.",
        source = "docs/index.md",
      ),
    ),
    healthChecks = List(
      DatalakeHealthCheck(
        label = "Scala format and tests",
        command = "TESTCONTAINERS=0 ./sbt scalafmtAll test",
        expectation = "All non-container suites pass.",
      ),
      DatalakeHealthCheck(
        label = "Executable documentation",
        command = "./sbt docs/mdoc checkDocSnippets",
        expectation = "All checked Scala snippets compile and generated Markdown is current.",
      ),
      DatalakeHealthCheck(
        label = "Public site",
        command = "./sbt buildDocsAssets && npm ci --prefix docs && npm run docs:build --prefix docs",
        expectation = "Scala.js, Scaladoc, and VitePress assets build without broken links.",
      ),
    ),
    operationalConfidence = List(
      DatalakeOperationalNote(
        label = "Evidence policy",
        description = "Static examples are labeled as reference data; only externally published updates appear as runtime events.",
      ),
      DatalakeOperationalNote(
        label = "Durability boundary",
        description = "Manifest deletion intentionally retains shared content-addressed blocks for deduplication.",
      ),
      DatalakeOperationalNote(
        label = "API stability",
        description = "Runtime interfaces are the integration anchor; the HTTP surface remains pre-1.0 and explicitly documented.",
      ),
    ),
    upcomingFocus = List(
      "Promote the RocksDB key-value adapter into a CAS backend and complete replica-index coordination.",
      "Add authenticated, versioned HTTP contracts before a stable REST release.",
      "Publish benchmark methodology before making performance claims.",
    ),
    sources = List(
      DatalakeSourceLink("CAS round-trip tests", "modules/graviton-runtime/src/test/scala/graviton/runtime/stores/CasRoundTripSpec.scala"),
      DatalakeSourceLink(
        "Filesystem manifest tests",
        "modules/graviton-runtime/src/test/scala/graviton/runtime/stores/FsBlobManifestRepoSpec.scala",
      ),
      DatalakeSourceLink("HTTP contract tests", "modules/protocol/graviton-http/src/test/scala/graviton/protocol/http/HttpApiSpec.scala"),
      DatalakeSourceLink("CI workflows", ".github/workflows/ci.yml"),
    ),
  )
