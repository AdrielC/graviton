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
    case object Dashboard  extends Page
    case object Explorer   extends Page
    case object Upload     extends Page
    case object Stats      extends Page
    case object Schema     extends Page
    case object Updates    extends Page
    case object Mission    extends Page
    case object Pipeline   extends Page
    case object Playground extends Page
  }

  private def pageHref(page: Page): String = page match
    case Page.Dashboard  => "#/"
    case Page.Explorer   => "#/explorer"
    case Page.Upload     => "#/upload"
    case Page.Stats      => "#/stats"
    case Page.Schema     => "#/schema"
    case Page.Updates    => "#/updates"
    case Page.Mission    => "#/mission"
    case Page.Pipeline   => "#/pipeline"
    case Page.Playground => "#/playground"

  val dashboardRoute  = Route.static(Page.Dashboard, root / endOfSegments)
  val explorerRoute   = Route.static(Page.Explorer, root / "explorer" / endOfSegments)
  val uploadRoute     = Route.static(Page.Upload, root / "upload" / endOfSegments)
  val statsRoute      = Route.static(Page.Stats, root / "stats" / endOfSegments)
  val schemaRoute     = Route.static(Page.Schema, root / "schema" / endOfSegments)
  val updatesRoute    = Route.static(Page.Updates, root / "updates" / endOfSegments)
  val missionRoute    = Route.static(Page.Mission, root / "mission" / endOfSegments)
  val pipelineRoute   = Route.static(Page.Pipeline, root / "pipeline" / endOfSegments)
  val playgroundRoute = Route.static(Page.Playground, root / "playground" / endOfSegments)

  def pageFromLocation(value: String): Page = value match
    case s if s.contains("playground") => Page.Playground
    case s if s.contains("pipeline")   => Page.Pipeline
    case s if s.contains("explorer")   => Page.Explorer
    case s if s.contains("upload")     => Page.Upload
    case s if s.contains("stats")      => Page.Stats
    case s if s.contains("schema")     => Page.Schema
    case s if s.contains("updates")    => Page.Updates
    case s if s.contains("mission")    => Page.Mission
    case _                             => Page.Dashboard

  val router = new Router[Page](
    routes =
      List(dashboardRoute, explorerRoute, uploadRoute, statsRoute, schemaRoute, updatesRoute, missionRoute, pipelineRoute, playgroundRoute),
    getPageTitle = {
      case Page.Dashboard  => "Graviton - Dashboard"
      case Page.Explorer   => "Graviton - Blob Explorer"
      case Page.Upload     => "Graviton - File Upload"
      case Page.Stats      => "Graviton - Statistics"
      case Page.Schema     => "Graviton - Schema Viewer"
      case Page.Updates    => "Graviton - Datalake Updates"
      case Page.Mission    => "Graviton - Capacity Lab"
      case Page.Pipeline   => "Graviton - Pipeline Explorer"
      case Page.Playground => "Graviton - CAS Playground"
    },
    serializePage = {
      case Page.Dashboard  => "#/"
      case Page.Explorer   => "#/explorer"
      case Page.Upload     => "#/upload"
      case Page.Stats      => "#/stats"
      case Page.Schema     => "#/schema"
      case Page.Updates    => "#/updates"
      case Page.Mission    => "#/mission"
      case Page.Pipeline   => "#/pipeline"
      case Page.Playground => "#/playground"
    },
    deserializePage = pageFromLocation,
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
          navLink(Page.Playground, "🧪 CAS Lab"),
          navLink(Page.Updates, "🛰️ Updates"),
          navLink(Page.Mission, "🧮 Capacity Lab"),
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
                  "Demo mode: API-backed views are using reference data. Start a Graviton server at http://localhost:8081 to connect live.",
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
        p(cls := "page-intro", "Explore the working CAS model, current capability boundaries, and browser-side learning tools."),
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
              li("💾 Filesystem CAS plus S3 blocks and PostgreSQL manifests"),
              li("🧱 A durable RocksDB key-value adapter with CAS wiring still planned"),
              li("🔐 Cryptographic hashing and verification"),
              li("📊 Observable with Prometheus metrics"),
              li("⚡ Bounded streaming with ZIO"),
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
                p("Inspect the clearly labeled reference blob dataset"),
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
                p("Analyze local files in the browser without uploading them"),
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
                p("Inspect process counters or a clearly labeled demo fallback"),
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
                p("Inspect shared data models from Scala.js and ZIO Schema"),
              ),
            ),
          ),
        ),
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
          "Compose transducer stages interactively. This component uses the shared PipelineCatalog, the same model the JVM runtime uses.",
        ),
        PipelineExplorer(),
      )

    case Page.Playground =>
      div(
        cls := "page-playground",
        CasPlayground(),
      )
  }
}
