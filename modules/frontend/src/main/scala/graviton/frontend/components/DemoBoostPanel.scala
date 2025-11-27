package graviton.frontend.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.tags.HtmlTag
import graviton.frontend.GravitonApi

import scala.util.Random

/** Playground that lets the /demo page feel alive without a live backend. */
object DemoBoostPanel {

  private val rand = new Random()

  private final case class DemoEvent(
    text: String,
    throughputDelta: Double,
    dedupDelta: Double,
    latencyDelta: Double,
  )

  private val autopilotCatalog: List[DemoEvent] = List(
    DemoEvent("Fast ingest burst from CLI pipeline (+256 chunks)", 48, 1.8, 2.5),
    DemoEvent("Async replication drained queue across shards", 28, 0.7, -3.0),
    DemoEvent("Manifest compactor reclaimed cold sectors", 12, 2.9, -1.5),
    DemoEvent("S3 tier sync verified 1,024 frames", -8, 0.4, 4.2),
    DemoEvent("RocksDB cache warmed with hot blobs", 34, 1.1, -2.4),
  )

  def apply(api: GravitonApi): HtmlElement = {
    val throughputVar  = Var(360.0) // MB/s
    val dedupSavings   = Var(71.0)  // %
    val latencyBudget  = Var(34.0)  // ms
    val concurrencyVar = Var(12)
    val autopilotVar   = Var(true)
    val activityVar    = Var(List("Demo Boost Lab primed. Toggle autopilot or fire manual events."))

    def clamp(value: Double, min: Double, max: Double): Double =
      math.max(min, math.min(max, value))

    def record(event: String): Unit =
      activityVar.update(events => (event :: events).take(6))

    def applyEvent(event: DemoEvent): Unit = {
      throughputVar.update(current => clamp(current + event.throughputDelta, 80, 1800))
      dedupSavings.update(current => clamp(current + event.dedupDelta, 10, 99))
      latencyBudget.update(current => clamp(current + event.latencyDelta, 6, 180))
      record(event.text)
    }

    def randomEvent(): DemoEvent =
      autopilotCatalog(rand.nextInt(autopilotCatalog.length))

    val autopilotStream =
      autopilotVar.signal.flatMapSwitch {
        case true  => EventStream.periodic(3500).mapTo(randomEvent())
        case false => EventStream.empty
      }

    autopilotStream.foreach(applyEvent)(unsafeWindowOwner)

    concurrencyVar.signal.changes.foreach { fibers =>
      val delta = DemoEvent(s"Concurrency tuned to $fibers ingest fibers", fibers * 1.8, 0.3, -math.min(4.5, fibers / 6.0))
      applyEvent(delta)
    }(unsafeWindowOwner)

    val projectedThroughput = throughputVar.signal.combineWith(concurrencyVar.signal).map { (base, fibers) =>
      val boostFactor = 0.6 + (fibers / 24.0)
      clamp(base * boostFactor, 120, 2000)
    }

    val progressTag = HtmlTag("progress")

    def manualButton(label: String, emoji: String, event: DemoEvent) =
      button(
        cls := "btn-primary boost-btn",
        s"$emoji $label",
        onClick.mapTo(event) --> Observer[DemoEvent](applyEvent),
      )

    div(
      cls := "demo-boost-panel",
      h2("🚀 Demo Boost Lab"),
      p(
        cls := "page-intro",
        "Experiment with ingest bursts, concurrency tuning, and simulated replication events. Everything updates live without needing a running cluster.",
      ),
      child <-- api.offlineSignal.map { offline =>
        if (offline)
          div(cls := "demo-hint", "Using canned data. Point the docs at a live Graviton node to mirror real metrics.")
        else emptyNode
      },
      div(
        cls := "boost-metrics",
        div(
          cls := "boost-metric",
          span(cls  := "metric-label", "Live Throughput"),
          strong(child.text <-- throughputVar.signal.map(value => f"$value%.0f MB/s")),
          progressTag(
            minAttr := "0",
            maxAttr := "2000",
            value <-- throughputVar.signal.map(_.toString),
          ),
        ),
        div(
          cls := "boost-metric",
          span(cls := "metric-label", "Projected Throughput"),
          strong(child.text <-- projectedThroughput.map(value => f"$value%.0f MB/s")),
          small(child.text <-- concurrencyVar.signal.map(f => s"Concurrency boost: $f ingest fibers")),
        ),
        div(
          cls := "boost-metric",
          span(cls  := "metric-label", "Dedup Savings"),
          strong(child.text <-- dedupSavings.signal.map(value => f"$value%.1f%%")),
          progressTag(
            minAttr := "0",
            maxAttr := "100",
            value <-- dedupSavings.signal.map(_.toString),
          ),
        ),
        div(
          cls := "boost-metric",
          span(cls  := "metric-label", "Latency Budget"),
          strong(child.text <-- latencyBudget.signal.map(value => f"$value%.0f ms")),
          progressTag(
            minAttr := "0",
            maxAttr := "180",
            value <-- latencyBudget.signal.map(_.toString),
          ),
        ),
      ),
      div(
        cls := "boost-controls",
        manualButton("Simulate Ingest Burst", "⚡", DemoEvent("CLI ingest burst moved 512 chunks", 72, 2.4, 1.8)),
        manualButton("Trigger Repair Cycle", "🛠️", DemoEvent("Repair job reconciled manifests", 18, 3.1, -4.0)),
        manualButton("Replicate to S3", "☁️", DemoEvent("Cross-region sync pushed 1.2 GiB to S3", 12, 0.9, 3.6)),
        label(
          cls := "autopilot-toggle",
          input(
            tpe := "checkbox",
            checked <-- autopilotVar.signal,
            onInput.mapToChecked --> autopilotVar.writer,
          ),
          span("Autopilot events"),
        ),
      ),
      div(
        cls := "boost-slider",
        label(
          cls      := "slider-label",
          span("Concurrent ingest fibers"),
          strong(child.text <-- concurrencyVar.signal.map(count => s"$count")),
        ),
        input(
          cls      := "range-control",
          tpe      := "range",
          minAttr  := "1",
          maxAttr  := "64",
          stepAttr := "1",
          controlled(
            value <-- concurrencyVar.signal.map(_.toString),
            onInput.mapToValue --> Observer[String](raw => raw.toIntOption.foreach(v => concurrencyVar.set(v))),
          ),
        ),
      ),
      div(
        cls := "boost-activity",
        h3("📡 Activity Feed"),
        ul(
          cls := "activity-feed",
          children <-- activityVar.signal.map(_.map(event => li(event))),
        ),
      ),
    )
  }
}
