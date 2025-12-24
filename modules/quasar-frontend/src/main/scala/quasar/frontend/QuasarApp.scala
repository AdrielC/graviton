package quasar.frontend

import com.raquo.laminar.api.L.*
import com.raquo.laminar.tags.HtmlTag
import com.raquo.waypoint.*
import org.scalajs.dom
import quasar.frontend.components.*

object QuasarApp {

  sealed trait Page
  object Page {
    case object Home         extends Page
    case object LegacyImport extends Page
  }

  private def pageHref(page: Page): String = page match
    case Page.Home         => "#/"
    case Page.LegacyImport => "#/legacy-import"

  val homeRoute         = Route.static(Page.Home, root / endOfSegments)
  val legacyImportRoute = Route.static(Page.LegacyImport, root / "legacy-import" / endOfSegments)

  val router = new Router[Page](
    routes = List(homeRoute, legacyImportRoute),
    getPageTitle = {
      case Page.Home         => "Quasar - Home"
      case Page.LegacyImport => "Quasar - Legacy import"
    },
    serializePage = {
      case Page.Home         => "#/"
      case Page.LegacyImport => "#/legacy-import"
    },
    deserializePage = {
      case s if s.contains("legacy-import") => Page.LegacyImport
      case _                                => Page.Home
    },
  )(
    popStateEvents = windowEvents(_.onPopState),
    owner = unsafeWindowOwner,
  )

  def apply(baseUrl: String, docsBase: String): HtmlElement = {
    val api = QuasarApi(baseUrl, BrowserHttpClient(baseUrl))

    val docsBaseNormalized =
      val trimmed = docsBase.trim
      if trimmed.isEmpty || trimmed == "/" then ""
      else if trimmed.endsWith("/") then trimmed.dropRight(1)
      else trimmed

    def docHref(path: String): String =
      val normalizedPath = if path.startsWith("/") then path else s"/$path"
      s"$docsBaseNormalized$normalizedPath"

    div(
      // Reuse the docs demo CSS (defined for `.graviton-app`) to keep this looking decent without
      // introducing a second style system.
      cls := "graviton-app quasar-app",
      HtmlTag("header")(
        cls := "app-header",
        div(
          cls := "header-content",
          h1(cls := "app-title", "Quasar"),
          p(cls  := "app-subtitle", "Document platform (Scala.js + Laminar)"),
        ),
        HtmlTag("nav")(
          cls := "app-nav",
          navLink(Page.Home, "🏠 Home"),
          navLink(Page.LegacyImport, "🧾 Legacy import"),
        ),
        div(
          cls := "header-health",
          HealthCheck(api),
        ),
      ),
      HtmlTag("main")(
        cls := "app-content",
        child <-- router.currentPageSignal.map { page =>
          renderPage(page, api)
        },
      ),
      HtmlTag("footer")(
        cls := "app-footer",
        p("Built with ZIO • Powered by Scala 3 • UI with Laminar"),
        p(
          a(href := docHref("/api/quasar-http-v1"), "Quasar API docs"),
          " • ",
          a(href := docHref("/api/legacy-repos"), "Legacy repos"),
        ),
      ),
    )
  }

  private def navLink(page: Page, label: String): HtmlElement =
    a(
      cls  := "nav-link",
      cls <-- router.currentPageSignal.map { current =>
        if current == page then "active" else ""
      },
      href := pageHref(page),
      label,
      onClick --> { (event: dom.MouseEvent) =>
        event.preventDefault()
        event.stopPropagation()
        router.pushState(page)
      },
    )

  private def renderPage(page: Page, api: QuasarApi): HtmlElement = page match {
    case Page.Home =>
      div(
        h2("Quasar UI"),
        p("This is a minimal Laminar shell intended to grow into the Quasar tenant-implicit UI."),
        ul(
          li("Health check via ", code("GET /v1/health")),
          li("Legacy import via ", code("POST /v1/legacy/import")),
        ),
      )

    case Page.LegacyImport =>
      LegacyImport(api)
  }
}
