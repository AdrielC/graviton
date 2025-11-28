package graviton.frontend.components

import com.raquo.laminar.api.L.*
import graviton.frontend.GravitonApi
import graviton.shared.ApiModels.*
import graviton.shared.schema.SchemaExplorer
import org.scalajs.dom
import zio.*
import zio.json.*

import scala.concurrent.ExecutionContext.Implicits.global

/** Interactive datalake change dashboard used inside the Scala.js demo. */
object DatalakeDashboardView {

  private sealed trait StreamState
  private object StreamState {
    case object Disabled   extends StreamState
    case object Connecting extends StreamState
    case object Connected  extends StreamState
    case object Error      extends StreamState
  }

  private def statusClass(status: String): String = {
    val normalized = status.toLowerCase
    if (normalized.contains("✅") || normalized.contains("green")) "status-green"
    else if (normalized.contains("🚧") || normalized.contains("progress")) "status-progress"
    else if (normalized.contains("⚠") || normalized.contains("warn")) "status-warn"
    else "status-neutral"
  }

  def apply(api: GravitonApi): HtmlElement = {
    val dashboardVar   = Var[Option[DatalakeDashboard]](None)
    val metaschemaVar  = Var[Option[DatalakeMetaschema]](None)
    val explorerVar    = Var[Option[SchemaExplorer.Graph]](None)
    val loadingVar     = Var(false)
    val errorVar       = Var[Option[String]](None)
    val streamStateVar = Var[StreamState](StreamState.Disabled)
    val eventSourceVar = Var[Option[dom.EventSource]](None)
    val runtime        = Runtime.default

    def loadDashboard(): Unit = {
      loadingVar.set(true)
      errorVar.set(None)

      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.getDatalakeDashboard).onComplete {
          case scala.util.Success(data)  =>
            dashboardVar.set(Some(data.snapshot))
            metaschemaVar.set(Some(data.metaschema))
            explorerVar.set(Some(data.schemaExplorer))
            loadingVar.set(false)
          case scala.util.Failure(error) =>
            errorVar.set(Some(error.getMessage))
            loadingVar.set(false)
        }
      }
    }

    def startStream(): Unit =
      api.dashboardStreamUrl match
        case None      =>
          streamStateVar.set(StreamState.Disabled)
        case Some(url) =>
          streamStateVar.set(StreamState.Connecting)
          val source = new dom.EventSource(url)
          eventSourceVar.set(Some(source))
          source.onopen = _ => streamStateVar.set(StreamState.Connected)
          source.onerror = _ => streamStateVar.set(StreamState.Error)
          source.onmessage = (event: dom.MessageEvent) =>
            event.data.toString.fromJson[DatalakeDashboard] match
              case Left(err)   => dom.console.error(s"Failed to decode dashboard SSE payload: $err")
              case Right(data) =>
                dashboardVar.set(Some(data))

    def stopStream(): Unit = {
      eventSourceVar.now().foreach(_.close())
      eventSourceVar.set(None)
    }

    // Prime the UI with an initial snapshot + metaschema.
    loadDashboard()
    startStream()

    div(
      cls := "datalake-dashboard",
      onUnmountCallback(_ => stopStream()),
      div(
        cls := "datalake-header",
        div(
          cls := "datalake-heading",
          h1("🛰️ Datalake Change Dashboard"),
          p(cls := "datalake-subtitle", "Track ingest, runtime, and experience updates without leaving the Scala.js demo."),
        ),
        div(
          cls := "datalake-actions",
          div(
            cls := "stream-status",
            child <-- streamStateVar.signal.map(renderStreamStatus),
          ),
          button(
            cls := "btn-primary",
            "🔄 Refresh dashboard",
            onClick --> { _ => loadDashboard() },
            disabled <-- loadingVar.signal,
          ),
        ),
      ),
      child <-- api.offlineSignal.map { offline =>
        if (offline)
          div(
            cls := "demo-hint",
            "Showing static dashboard data from DemoData. Connect a live Graviton server to fetch the latest updates.",
          )
        else emptyNode
      },
      child <-- dashboardVar.signal.map {
        case None       => div(cls := "datalake-empty", "Click refresh to load the datalake summary.")
        case Some(data) => renderDashboard(data, metaschemaVar, explorerVar)
      },
      child <-- errorVar.signal.map {
        case None        => emptyNode
        case Some(error) => div(cls := "error-message", s"⚠️ Unable to load dashboard: $error")
      },
      child <-- loadingVar.signal.map { loading =>
        if (loading) div(cls := "loading-spinner", "⏳ Loading dashboard...")
        else emptyNode
      },
    )
  }

  private def renderStreamStatus(state: StreamState): HtmlElement =
    state match
      case StreamState.Disabled   => span(cls := "status-neutral", "SSE disabled")
      case StreamState.Connecting => span(cls := "status-progress", "Connecting…")
      case StreamState.Connected  => span(cls := "status-green", "Streaming live")
      case StreamState.Error      => span(cls := "status-warn", "Stream error (using cached data)")

  private def renderDashboard(data: DatalakeDashboard, metaschemaVar: Var[Option[DatalakeMetaschema]]): HtmlElement = {
    div(
      div(
        cls := "datalake-meta",
        div(
          cls := "datalake-meta-card",
          span(cls := "meta-label", "Last updated"),
          span(cls := "meta-value", data.lastUpdated),
        ),
        div(
          cls := "datalake-meta-card",
          span(cls := "meta-label", "Branch"),
          span(cls := "meta-value", data.branch),
        ),
      ),
      datalakeSection(
        "Executive Snapshot",
        div(
          cls := "datalake-pillars",
          data.pillars.map { pillar =>
            div(
              cls := "datalake-pillar-card",
              div(cls := s"pillar-status ${statusClass(pillar.status)}", pillar.status),
              h3(pillar.title),
              p(cls   := "pillar-evidence", pillar.evidence),
              p(cls   := "pillar-impact", pillar.impact),
            )
          },
        ),
      ),
      datalakeSection(
        "Recent Highlights",
        div(
          cls := "datalake-highlights",
          data.highlights.map { highlight =>
            div(
              cls := "highlight-card",
              h3(highlight.category),
              ul(highlight.bullets.map(li(_))*),
            )
          },
        ),
      ),
      datalakeSection(
        "Change Stream (30 days)",
        table(
          cls := "change-stream-table",
          thead(
            tr(
              th("Date"),
              th("Area"),
              th("Update"),
              th("Impact"),
              th("Source"),
            )
          ),
          tbody(
            data.changeStream.map { entry =>
              tr(
                td(entry.date),
                td(entry.area),
                td(entry.update),
                td(entry.impact),
                td(entry.source),
              )
            }
          ),
        ),
      ),
      datalakeSection(
        "Health Indicators",
        div(
          cls := "datalake-health-grid",
          div(
            cls := "health-checks",
            h3("Verification Commands"),
            table(
              cls := "health-check-table",
              tbody(
                data.healthChecks.map { check =>
                  tr(
                    td(
                      div(cls := "health-label", check.label),
                      code(check.command),
                    ),
                    td(check.expectation),
                  )
                }
              ),
            ),
          ),
          div(
            cls := "operational-confidence",
            h3("Operational Confidence"),
            ul(
              data.operationalConfidence.map { note =>
                li(span(cls := "note-label", note.label + ": "), span(note.description))
              }*
            ),
          ),
        ),
      ),
      datalakeSection(
        "Upcoming Focus",
        ul(
          cls := "upcoming-list",
          data.upcomingFocus.map(item => li("• " + item))*
        ),
      ),
      datalakeSection(
        "Source Index",
        div(
          cls := "source-chips",
          data.sources.map { source =>
            div(
              cls := "source-chip",
              span(cls := "source-label", source.label),
              span(cls := "source-path", source.path),
            )
          },
        ),
      ),
      datalakeSection(
        "Metaschema",
        div(
          cls := "metaschema-card",
          child <-- metaschemaVar.signal.map {
            case None         => div(cls := "datalake-empty", "Schema metadata unavailable.")
            case Some(schema) => pre(code(schema.astJson))
          },
        ),
      ),
    )
  }

  private def datalakeSection(title: String, content: HtmlElement): HtmlElement =
    div(
      cls := "datalake-section",
      div(
        cls := "section-title",
        h2(title),
      ),
      content,
    )
}
