package graviton.frontend

import com.raquo.laminar.api.L.{*, given}
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
  }

  val dashboardRoute = Route.static(Page.Dashboard, root / endOfSegments)
  val explorerRoute  = Route.static(Page.Explorer, root / "explorer" / endOfSegments)
  val uploadRoute    = Route.static(Page.Upload, root / "upload" / endOfSegments)
  val statsRoute     = Route.static(Page.Stats, root / "stats" / endOfSegments)

  val router = new Router[Page](
    routes = List(dashboardRoute, explorerRoute, uploadRoute, statsRoute),
    getPageTitle = {
      case Page.Dashboard => "Graviton - Dashboard"
      case Page.Explorer  => "Graviton - Blob Explorer"
      case Page.Upload    => "Graviton - File Upload"
      case Page.Stats     => "Graviton - Statistics"
    },
    serializePage = {
      case Page.Dashboard => "#/"
      case Page.Explorer  => "#/explorer"
      case Page.Upload    => "#/upload"
      case Page.Stats     => "#/stats"
    },
    deserializePage = {
      case s if s.contains("explorer") => Page.Explorer
      case s if s.contains("upload")   => Page.Upload
      case s if s.contains("stats")    => Page.Stats
      case _                           => Page.Dashboard
    },
  )(
    popStateEvents = windowEvents(_.onPopState),
    owner = unsafeWindowOwner,
  )

  def apply(baseUrl: String): HtmlElement = {
    val api = new GravitonApi(new BrowserHttpClient(baseUrl))

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
        ),

        // Health indicator
        div(cls := "header-health", HealthCheck(api)),
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
          a(href := "/graviton/api", "API Docs"),
          " • ",
          a(href := "/graviton/scaladoc/index.html", target       := "_blank", "Scaladoc"),
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
      href := router.absoluteUrlForPage(page),
      label,
      onClick.preventDefault --> { _ =>
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
              href := router.absoluteUrlForPage(Page.Explorer),
              onClick.preventDefault --> { _ => router.pushState(Page.Explorer) },
              div(
                cls := "feature-card",
                "🔍 Explore Blobs",
                p("Search and inspect blob metadata and manifests"),
              ),
            ),
            a(
              cls  := "feature-card-link",
              href := router.absoluteUrlForPage(Page.Upload),
              onClick.preventDefault --> { _ => router.pushState(Page.Upload) },
              div(
                cls := "feature-card",
                "📤 Upload Files",
                p("See chunking in action and explore deduplication"),
              ),
            ),
            a(
              cls  := "feature-card-link",
              href := router.absoluteUrlForPage(Page.Stats),
              onClick.preventDefault --> { _ => router.pushState(Page.Stats) },
              div(
                cls := "feature-card",
                "📊 View Statistics",
                p("Monitor system metrics and deduplication ratios"),
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
  }
}
