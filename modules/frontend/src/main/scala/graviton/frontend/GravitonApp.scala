package graviton.frontend

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*
import com.raquo.laminar.tags.HtmlTag

import graviton.frontend.components.*

import org.scalajs.dom

/** Main Graviton frontend application */
object GravitonApp {

  sealed trait Page
  object Page {
    case object Dashboard extends Page
    case object Explorer  extends Page
    case object Upload    extends Page
    case object Stats     extends Page
    case object Schema    extends Page
    case object Updates   extends Page
    case object Mission   extends Page
    case object Pipeline  extends Page
  }

  private def pageHref(page: Page): String = page match
    case Page.Dashboard => "#/"
    case Page.Explorer  => "#/explorer"
    case Page.Upload    => "#/upload"
    case Page.Stats     => "#/stats"
    case Page.Schema    => "#/schema"
    case Page.Updates   => "#/updates"
    case Page.Mission   => "#/mission"
    case Page.Pipeline  => "#/pipeline"

  val dashboardRoute = Route.static(Page.Dashboard, root / endOfSegments)
  val explorerRoute  = Route.static(Page.Explorer, root / "explorer" / endOfSegments)
  val uploadRoute    = Route.static(Page.Upload, root / "upload" / endOfSegments)
  val statsRoute     = Route.static(Page.Stats, root / "stats" / endOfSegments)
  val schemaRoute    = Route.static(Page.Schema, root / "schema" / endOfSegments)
  val updatesRoute   = Route.static(Page.Updates, root / "updates" / endOfSegments)
  val missionRoute   = Route.static(Page.Mission, root / "mission" / endOfSegments)
  val pipelineRoute  = Route.static(Page.Pipeline, root / "pipeline" / endOfSegments)

  val router = new Router[Page](
    routes = List(dashboardRoute, explorerRoute, uploadRoute, statsRoute, schemaRoute, updatesRoute, missionRoute, pipelineRoute),
    getPageTitle = {
      case Page.Dashboard => "Graviton - Dashboard"
      case Page.Explorer  => "Graviton - Blob Explorer"
      case Page.Upload    => "Graviton - File Upload"
      case Page.Stats     => "Graviton - Statistics"
      case Page.Schema    => "Graviton - Schema Viewer"
      case Page.Updates   => "Graviton - Datalake Updates"
      case Page.Mission   => "Graviton - Mission Control"
      case Page.Pipeline  => "Graviton - Pipeline Explorer"
    },
    serializePage = {
      case Page.Dashboard => "#/"
      case Page.Explorer  => "#/explorer"
      case Page.Upload    => "#/upload"
      case Page.Stats     => "#/stats"
      case Page.Schema    => "#/schema"
      case Page.Updates   => "#/updates"
      case Page.Mission   => "#/mission"
      case Page.Pipeline  => "#/pipeline"
    },
    deserializePage = {
      case s if s.contains("pipeline") => Page.Pipeline
      case s if s.contains("explorer") => Page.Explorer
      case s if s.contains("upload")   => Page.Upload
      case s if s.contains("stats")    => Page.Stats
      case s if s.contains("schema")   => Page.Schema
      case s if s.contains("updates")  => Page.Updates
      case s if s.contains("mission")  => Page.Mission
      case _                           => Page.Dashboard
    },
  )(
    popStateEvents = windowEvents(_.onPopState),
    owner = unsafeWindowOwner,
  )

  def apply(baseUrl: String, docsBase: String): HtmlElement = {
    val api                = GravitonApi(baseUrl, new BrowserHttpClient(baseUrl))
    val docsBaseNormalized =
      val trimmed = docsBase.trim
      if trimmed.isEmpty || trimmed == "/" then ""
      else if trimmed.endsWith("/") then trimmed.dropRight(1)
      else trimmed

    def docHref(path: String): String =
      val normalizedPath = if path.startsWith("/") then path else s"/$path"
      s"$docsBaseNormalized$normalizedPath"

    div(
      cls := "graviton-app",

      // Header with navigation
      HtmlTag("header")(
        cls := "app-header",
        div(cls := "header-content", h1(cls := "app-title", "⚡ Graviton"), p(cls := "app-subtitle", "Content-Addressable Storage Runtime")),
        HtmlTag("nav")(
          cls   := "app-nav",
          navLink(Page.Dashboard, "🏠 Dashboard"),
          navLink(Page.Explorer, "🔍 Explorer"),
          navLink(Page.Upload, "📤 Upload"),
          navLink(Page.Stats, "📊 Stats"),
          navLink(Page.Schema, "🧬 Schema"),
          navLink(Page.Pipeline, "⚡ Pipeline"),
          navLink(Page.Updates, "🛰️ Updates"),
          navLink(Page.Mission, "🛠️ Mission Control"),
        ),

        // Health indicator
        div(
          cls   := "header-health",
          HealthCheck(api),
          child <-- api.offlineSignal.map { offline =>
            if offline then
              div(
                cls := "demo-banner",
                span(cls := "demo-icon", "🛰️"),
                span(
                  cls    := "demo-text",
                  "Demo mode: responses are simulated. Start a Graviton server at http://localhost:8080 to connect to a live API.",
                ),
              )
            else emptyNode
          },
        ),
      ),

      // Main content area
      HtmlTag("main")(
        cls := "app-content",
        child <-- router.currentPageSignal.map { page =>
          renderPage(page, api)
        },
      ),

      // Footer
      HtmlTag("footer")(
        cls := "app-footer",
        p("⚡ Built with ZIO • Powered by Scala 3 • Interactive UI with Laminar"),
        p(
          a(href := "https://github.com/AdrielC/graviton", target := "_blank", "GitHub"),
          " • ",
          a(href := docHref("/api"), "API Docs"),
          " • ",
          a(href := docHref("/scaladoc/"), target                 := "_blank", "Scaladoc"),
        ),
      ),
    )
  }

  private def navLink(page: Page, label: String): HtmlElement =
    a(
      cls  := "nav-link",
      cls <-- router.currentPageSignal.map { current =>
        if (current == page) "active" else ""
      },
      href := pageHref(page),
      label,
      onClick --> { (event: dom.MouseEvent) =>
        event.preventDefault()
        event.stopPropagation()
        router.pushState(page)
      },
    )

  private def renderPage(page: Page, api: GravitonApi): HtmlElement = page match {
    case Page.Dashboard =>
      div(
        cls := "page-dashboard",
        h1("🏠 Dashboard"),
        p(cls := "page-intro", "Welcome to Graviton! Explore the interactive components below to learn about content-addressable storage."),
        div(
          cls := "dashboard-grid",
          div(
            cls := "feature-highlight",
            h3("⚡ What is Graviton?"),
            p("""
              Graviton is a modular content-addressable storage runtime built on ZIO.
              It provides deduplication, streaming, and multi-backend support for large binary payloads.
            """),
            ul(
              li("🎯 Content-defined chunking with FastCDC"),
              li("💾 Multiple storage backends (S3, PostgreSQL, RocksDB)"),
              li("🔐 Cryptographic hashing and verification"),
              li("📊 Observable with Prometheus metrics"),
              li("⚡ Zero-copy streaming with ZIO"),
            ),
          ),
          div(
            cls := "quick-links",
            h3("🚀 Quick Start"),
            a(
              cls  := "feature-card-link",
              href := pageHref(Page.Explorer),
              onClick --> { (event: dom.MouseEvent) =>
                event.preventDefault()
                event.stopPropagation()
                router.pushState(Page.Explorer)
              },
              div(
                cls := "feature-card",
                "🔍 Explore Blobs",
                p("Search and inspect blob metadata and manifests"),
              ),
            ),
            a(
              cls  := "feature-card-link",
              href := pageHref(Page.Upload),
              onClick --> { (event: dom.MouseEvent) =>
                event.preventDefault()
                event.stopPropagation()
                router.pushState(Page.Upload)
              },
              div(
                cls := "feature-card",
                "📤 Upload Files",
                p("See chunking in action and explore deduplication"),
              ),
            ),
            a(
              cls  := "feature-card-link",
              href := pageHref(Page.Stats),
              onClick --> { (event: dom.MouseEvent) =>
                event.preventDefault()
                event.stopPropagation()
                router.pushState(Page.Stats)
              },
              div(
                cls := "feature-card",
                "📊 View Statistics",
                p("Monitor system metrics and deduplication ratios"),
              ),
            ),
            a(
              cls  := "feature-card-link",
              href := pageHref(Page.Schema),
              onClick --> { (event: dom.MouseEvent) =>
                event.preventDefault()
                event.stopPropagation()
                router.pushState(Page.Schema)
              },
              div(
                cls := "feature-card",
                "🧬 Browse Schemas",
                p("Inspect shared data models straight from Scala.js + ZIO"),
              ),
            ),
          ),
        ),
        DemoBoostPanel(api),
        PipelineTimeline(),
      )

    case Page.Explorer =>
      div(
        cls := "page-explorer",
        BlobExplorer(api),
      )

    case Page.Upload =>
      div(
        cls := "page-upload",
        FileUpload(),
      )

    case Page.Stats =>
      div(
        cls := "page-stats",
        StatsPanel(api),
      )

    case Page.Schema =>
      div(
        cls := "page-schema",
        SchemaViewer(api),
      )

    case Page.Updates =>
      div(
        cls := "page-updates",
        DatalakeDashboardView(api),
      )

    case Page.Mission =>
      div(
        cls := "page-mission",
        MissionControl(api),
      )

    case Page.Pipeline =>
      div(
        cls := "page-pipeline",
        h1("⚡ Pipeline Explorer"),
        p(
          cls := "page-intro",
          "Compose transducer stages interactively. This component uses the shared PipelineCatalog — the same model the JVM runtime uses.",
        ),
        PipelineExplorer(),
      )
  }
}
