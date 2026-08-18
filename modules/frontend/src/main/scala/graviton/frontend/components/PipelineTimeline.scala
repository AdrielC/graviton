package graviton.frontend.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.tags.HtmlTag
import org.scalajs.dom
import scala.scalajs.js

/**
 * Animated timeline that showcases how data flows through the ingest, deduplication,
 * and replication pipelines. Designed purely for demo purposes so the UI feels alive
 * even without a live backend.
 */
object PipelineTimeline {

  private enum Severity:
    case Normal, Advisory, Critical

  private final case class TimelineEvent(
    title: String,
    summary: String,
    stage: String,
    location: String,
    latency: String,
    icon: String,
    severity: Severity,
  )

  private final case class RenderedEvent(
    id: Int,
    event: TimelineEvent,
    timestamp: String,
  )

  private val eventScript: List[TimelineEvent] = List(
    TimelineEvent(
      title = "FastCDC anchor locked",
      summary = "Window realigned to rolling hash peak; manifest builder verified boundaries.",
      stage = "Chunking",
      location = "ingest-alpha-02",
      latency = "4.6 ms",
      icon = "🧩",
      severity = Severity.Normal,
    ),
    TimelineEvent(
      title = "Manifest promoted",
      summary = "Block graph validated across 2 replicas and promoted to quorum store.",
      stage = "Manifest",
      location = "replica-omega",
      latency = "12.4 ms",
      icon = "📜",
      severity = Severity.Normal,
    ),
    TimelineEvent(
      title = "Replica drift detected",
      summary = "Cold tier lag exceeded budget; background sweeper dispatched new frames.",
      stage = "Replication",
      location = "eu-west-2",
      latency = "58.0 ms",
      icon = "🌍",
      severity = Severity.Advisory,
    ),
    TimelineEvent(
      title = "Dedup overlap spike",
      summary = "Shared chunk ratio peaked at 81% after ingesting 3 correlated payloads.",
      stage = "Dedup",
      location = "dedup-01",
      latency = "9.1 ms",
      icon = "🪟",
      severity = Severity.Normal,
    ),
    TimelineEvent(
      title = "CDC autopilot tuned",
      summary = "Adaptive heuristic narrowed min/max to clamp noisy archive ingest.",
      stage = "Autopilot",
      location = "cdc-orbit",
      latency = "3.3 ms",
      icon = "🛰️",
      severity = Severity.Normal,
    ),
    TimelineEvent(
      title = "Quarantine cleared",
      summary = "Retried chunk validated against SHA-256 + BLAKE3 and returned to pool.",
      stage = "Integrity",
      location = "sector-19",
      latency = "7.4 ms",
      icon = "🛡️",
      severity = Severity.Normal,
    ),
    TimelineEvent(
      title = "Backpressure asserted",
      summary = "S3 commit lane paused 180 ms while local NVMe cache absorbed burst.",
      stage = "Transport",
      location = "edge-jfk",
      latency = "0.18 s",
      icon = "🚦",
      severity = Severity.Advisory,
    ),
    TimelineEvent(
      title = "Replica health degraded",
      summary = "One shard reported checksum mismatch; repair job auto-scheduled.",
      stage = "Replication",
      location = "ap-southeast-2",
      latency = "--",
      icon = "🧯",
      severity = Severity.Critical,
    ),
  )

  def apply(): HtmlElement = {
    val renderedVar = Var[List[RenderedEvent]](Nil)
    val playingVar  = Var(true)

    var pointer        = 0
    var counter        = 0
    var intervalHandle = Option.empty[Int]
    var isPlaying      = true

    def nowLabel(): String =
      new js.Date().toLocaleTimeString()

    def nextEvent(): RenderedEvent =
      val event = eventScript(pointer % eventScript.length)
      pointer += 1
      counter += 1
      RenderedEvent(counter, event, nowLabel())

    def pushEvent(): Unit =
      val next = nextEvent()
      renderedVar.update(list => (next :: list).take(7))

    def startTicker(): Unit = {
      stopTicker()
      val handle = dom.window.setInterval(() => pushEvent(), 4200)
      intervalHandle = Some(handle)
      isPlaying = true
      playingVar.set(true)
    }

    def stopTicker(): Unit = {
      intervalHandle.foreach(dom.window.clearInterval)
      intervalHandle = None
      isPlaying = false
      playingVar.set(false)
    }

    def toggleTicker(): Unit =
      if (isPlaying) stopTicker() else startTicker()

    div(
      cls := "pipeline-timeline",
      onMountUnmountCallback(
        mount = _ => {
          if (renderedVar.now().isEmpty) {
            List.fill(4)(()) foreach { _ => pushEvent() }
          }
          startTicker()
        },
        unmount = _ => stopTicker(),
      ),
      HtmlTag("header")(
        cls := "timeline-header",
        div(
          cls := "timeline-title",
          h3("Live Pipeline Timeline"),
          p("Synthetic ingest events to illustrate how the runtime reacts moment-to-moment."),
        ),
        div(
          cls := "timeline-controls",
          button(
            cls := "btn-secondary timeline-toggle",
            child.text <-- playingVar.signal.map(if (_) "Pause Stream" else "Resume Stream"),
            onClick --> { _ => toggleTicker() },
          ),
          button(
            cls := "btn-primary timeline-nudge",
            "Inject Event",
            onClick --> { _ =>
              pushEvent()
            },
          ),
        ),
      ),
      child <-- renderedVar.signal.map { events =>
        val warnings = events.count(_.event.severity == Severity.Advisory)
        val critical = events.count(_.event.severity == Severity.Critical)
        val coverage = events.map(_.event.stage).distinct.size
        div(
          cls := "timeline-metrics",
          metric("Active Stages", coverage.toString, "💡"),
          metric("Advisories", warnings.toString, "⚠️"),
          metric("Critical", critical.toString, "🚨"),
        )
      },
      div(
        cls := "timeline-stream",
        children <-- renderedVar.signal.map(_.map(renderEventCard)),
      ),
    )
  }

  private def metric(label: String, value: String, icon: String): HtmlElement =
    div(
      cls := "timeline-metric",
      span(cls := "metric-icon", icon),
      div(
        cls    := "metric-copy",
        span(cls := "metric-value", value),
        span(cls := "metric-label", label),
      ),
    )

  private def renderEventCard(event: RenderedEvent): HtmlElement =
    val severityClass = event.event.severity match
      case Severity.Normal   => "normal"
      case Severity.Advisory => "advisory"
      case Severity.Critical => "critical"

    div(
      cls := s"timeline-event $severityClass",
      div(cls := "event-icon", event.event.icon),
      div(
        cls   := "event-body",
        HtmlTag("header")(
          cls := "event-header",
          h4(event.event.title),
          span(cls := "event-stage", event.event.stage),
        ),
        p(cls := "event-summary", event.event.summary),
        div(
          cls := "event-meta",
          span(cls := "meta-pill", s"${event.timestamp}"),
          span(cls := "meta-pill", event.event.location),
          span(cls := "meta-pill", s"latency ${event.event.latency}"),
        ),
      ),
    )
}
