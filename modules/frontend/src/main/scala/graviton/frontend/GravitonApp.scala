package graviton.frontend

import com.raquo.laminar.api.L.*
import com.raquo.laminar.tags.HtmlTag
import graviton.frontend.components.*
import org.scalajs.dom

/** Live operations console for a running Graviton service. */
object GravitonApp:

  sealed trait Page
  object Page:
    case object Dashboard extends Page
    case object Explorer  extends Page
    case object Upload    extends Page
    case object Stats     extends Page

  private def pageHref(page: Page): String = page match
    case Page.Dashboard => "#/"
    case Page.Explorer  => "#/explorer"
    case Page.Upload    => "#/upload"
    case Page.Stats     => "#/stats"

  def pageFromLocation(value: String): Page = value match
    case location if location.contains("explorer") => Page.Explorer
    case location if location.contains("upload")   => Page.Upload
    case location if location.contains("stats")    => Page.Stats
    case _                                         => Page.Dashboard

  private val pageVar = Var(pageFromLocation(dom.window.location.hash))

  def apply(baseUrl: String, docsBase: String): HtmlElement =
    val endpointVar        = Var(baseUrl)
    val tokenVar           = Var("")
    val api                = GravitonApi(
      baseUrl,
      BrowserHttpClient(baseUrl, () => Option(tokenVar.now()).map(_.trim).filter(_.nonEmpty)),
    )
    val docsBaseNormalized =
      val trimmed = docsBase.trim
      if trimmed.isEmpty || trimmed == "/" then ""
      else if trimmed.endsWith("/") then trimmed.dropRight(1)
      else trimmed

    def docHref(path: String): String =
      val normalizedPath = if path.startsWith("/") then path else s"/$path"
      s"$docsBaseNormalized$normalizedPath"

    def connect(): Unit =
      val endpoint = endpointVar.now().trim.stripSuffix("/")
      if endpoint.nonEmpty then
        dom.window.localStorage.setItem("graviton.apiUrl", endpoint)
        dom.window.location.reload()

    div(
      cls := "graviton-app",
      windowEvents(_.onPopState).map(_ => pageFromLocation(dom.window.location.hash)) --> pageVar.writer,
      HtmlTag("header")(
        cls := "app-header",
        div(
          cls := "header-content",
          div(h1(cls := "app-title", "Graviton"), p(cls := "app-subtitle", "Live content-addressed storage operations")),
          div(cls := "header-health", HealthCheck(api)),
        ),
        div(
          cls := "api-connection-bar",
          label(
            span("API endpoint"),
            input(
              tpe         := "url",
              placeholder := "http://localhost:8081",
              controlled(value <-- endpointVar.signal, onInput.mapToValue --> endpointVar.writer),
              onKeyPress --> { event => if event.key == "Enter" then connect() },
            ),
          ),
          label(
            span("Bearer token (optional)"),
            input(
              tpe         := "password",
              placeholder := "Kept only in this page's memory",
              controlled(value <-- tokenVar.signal, onInput.mapToValue --> tokenVar.writer),
            ),
          ),
          button(cls := "btn-secondary", "Connect", onClick --> { _ => connect() }),
          small("The token is never saved to local or session storage. Changing the endpoint reloads the console and clears it."),
        ),
        HtmlTag("nav")(
          cls := "app-nav",
          navLink(Page.Dashboard, "Operations"),
          navLink(Page.Upload, "Upload"),
          navLink(Page.Explorer, "Inventory"),
          navLink(Page.Stats, "Counters"),
        ),
      ),
      HtmlTag("main")(
        cls := "app-content",
        child <-- pageVar.signal.map(page => renderPage(page, api)),
      ),
      HtmlTag("footer")(
        cls := "app-footer",
        p("Scala 3, ZIO, Laminar, and the live Graviton HTTP API"),
        p(
          a(href := "https://github.com/AdrielC/graviton", target := "_blank", "GitHub"),
          " • ",
          a(href := docHref("/api/http"), "HTTP API"),
          " • ",
          a(href := docHref("/scaladoc/"), target                 := "_blank", "Scaladoc"),
        ),
      ),
    )

  private def navLink(page: Page, label: String): HtmlElement =
    a(
      cls  := "nav-link",
      cls("active") <-- pageVar.signal.map(_ == page),
      href := pageHref(page),
      label,
      onClick --> { event =>
        event.preventDefault()
        event.stopPropagation()
        dom.window.location.hash = pageHref(page)
        pageVar.set(page)
      },
    )

  private def renderPage(page: Page, api: GravitonApi): HtmlElement = page match
    case Page.Dashboard =>
      div(
        cls := "page-dashboard",
        h1("Live operations"),
        p(
          cls := "page-intro",
          "This console displays only responses from the configured Graviton process. A disconnected server produces an error state, never substitute data.",
        ),
        div(
          cls := "dashboard-grid",
          div(
            cls := "feature-highlight",
            h3("Operational path"),
            ol(
              li("Upload a real file through the streaming HTTP endpoint."),
              li("Inspect the durable manifest and its content-addressed blocks."),
              li("Read and hash the stored bytes again with server-side verification."),
              li("Download the exact bytes or delete the manifest."),
            ),
          ),
          div(
            cls := "quick-links",
            operationLink(Page.Upload, "Upload bytes", "Persist a file and inspect the actual ingest result"),
            operationLink(Page.Explorer, "Browse inventory", "Read stored manifests and block layouts"),
          ),
        ),
        StatsPanel(api),
      )
    case Page.Explorer  => BlobExplorer(api)
    case Page.Upload    => FileUpload(api)
    case Page.Stats     => StatsPanel(api)

  private def operationLink(page: Page, titleText: String, body: String): HtmlElement =
    a(
      cls  := "feature-card-link",
      href := pageHref(page),
      onClick --> { event =>
        event.preventDefault()
        event.stopPropagation()
        dom.window.location.hash = pageHref(page)
        pageVar.set(page)
      },
      div(cls := "feature-card", titleText, p(body)),
    )
