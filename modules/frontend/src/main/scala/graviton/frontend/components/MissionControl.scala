package graviton.frontend.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.tags.HtmlTag
import graviton.frontend.GravitonApi
import graviton.shared.ApiModels.SystemStats
import org.scalajs.dom
import zio.*

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Interactive capacity worksheet built from explicit user-editable assumptions.
 *
 * It intentionally avoids throughput, latency, durability, and sustainability
 * projections because the repository does not yet publish a reproducible
 * benchmark model for those values.
 */
object MissionControl:

  private val sectionTag = HtmlTag("section")

  private final case class Workload(
    id: String,
    title: String,
    summary: String,
    inputTiBPerDay: Double,
    duplicatePercent: Int,
    icon: String,
    assumptions: List[String],
  )

  private final case class CapacityPlan(
    workload: Workload,
    chunkSizeKiB: Int,
    duplicatePercent: Int,
  )

  private final case class CapacityEstimate(
    inputTiB: Double,
    duplicateTiB: Double,
    uniqueTiB: Double,
    approximateBlocks: Long,
  )

  private val workloads = List(
    Workload(
      id = "document-archive",
      title = "Document archive",
      summary = "Versioned office documents, PDFs, and scanned case material.",
      inputTiBPerDay = 0.8,
      duplicatePercent = 55,
      icon = "📚",
      assumptions = List(
        "The duplicate percentage is a planning input, not a measured Graviton result.",
        "Content-defined chunking may preserve more shared blocks after insertions than fixed chunking.",
        "Retention and unreachable-block collection must be designed separately.",
      ),
    ),
    Workload(
      id = "backup-stream",
      title = "Backup stream",
      summary = "Recurring snapshots with a high proportion of unchanged content.",
      inputTiBPerDay = 3.0,
      duplicatePercent = 70,
      icon = "🗄️",
      assumptions = List(
        "The estimate assumes duplicate blocks are already present in the selected store.",
        "Replica count and compression are excluded from this worksheet.",
        "Validate recovery time and failure behavior against your own storage system.",
      ),
    ),
    Workload(
      id = "media-ingress",
      title = "Media ingress",
      summary = "Large media objects with relatively little expected repetition.",
      inputTiBPerDay = 5.0,
      duplicatePercent = 12,
      icon = "🎞️",
      assumptions = List(
        "Already-compressed media often offers less block reuse than versioned documents.",
        "The block estimate assumes the selected target size and ignores final-block variance.",
        "Publish measured throughput only after running a documented benchmark on target hardware.",
      ),
    ),
  )

  def apply(api: GravitonApi): HtmlElement =
    val workloadVar  = Var(workloads.head)
    val chunkSizeVar = Var(1024)
    val duplicateVar = Var(workloads.head.duplicatePercent)
    val statsVar     = Var[Option[SystemStats]](None)
    val runtime      = Runtime.default

    def loadStats(): Unit =
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.getStats).onComplete {
          case scala.util.Success(stats) => statsVar.set(Some(stats))
          case scala.util.Failure(error) => dom.console.warn("Capacity Lab stats unavailable", error.getMessage)
        }
      }

    val _ = workloadVar.signal.changes.foreach(workload => duplicateVar.set(workload.duplicatePercent))(using unsafeWindowOwner)

    val planSignal =
      workloadVar.signal
        .combineWith(chunkSizeVar.signal, duplicateVar.signal)
        .map((workload, chunkSize, duplicatePercent) => CapacityPlan(workload, chunkSize, duplicatePercent))

    val estimateSignal = planSignal.map(estimate)
    val commandSignal  = planSignal.map(commands)

    div(
      cls := "mission-control",
      onMountCallback(_ => loadStats()),
      div(
        cls := "mission-hero",
        div(
          cls := "mission-hero-copy",
          h2("Capacity Lab"),
          p(
            cls := "page-intro",
            "Explore storage shape with transparent assumptions, then copy commands that work with the local filesystem CAS.",
          ),
          div(
            cls := "mission-hero-badges",
            span("Assumption driven"),
            span("No benchmark theater"),
            span("Runnable commands"),
          ),
        ),
        child <-- statsVar.signal.combineWith(api.offlineSignal).map {
          case (Some(stats), false) =>
            div(
              cls := "mission-hero-stats",
              statBlock("Process ingests", stats.totalBlobs.toString, "📦"),
              statBlock("Fresh blocks", stats.uniqueChunks.toString, "🧩"),
              statBlock("Dedup share", f"${stats.deduplicationRatio * 100}%.1f%%", "♻️"),
            )
          case _                    =>
            div(
              cls := "mission-hero-stats offline",
              statBlock("Mode", "Worksheet", "🧮"),
              statBlock("Inputs", "Editable", "✏️"),
              statBlock("Telemetry", "None", "✓"),
            )
        },
      ),
      sectionTag(
        cls := "mission-section",
        headerSection("Choose an example workload"),
        p(
          cls := "page-intro",
          "These presets are hypothetical starting points. Change the duplicate assumption before using the output for planning.",
        ),
        div(
          cls := "scenario-grid",
          workloads.map(workload => renderWorkloadCard(workload, workloadVar)),
        ),
      ),
      sectionTag(
        cls := "mission-section",
        headerSection("Set the storage assumptions"),
        div(
          cls := "mission-controls",
          rangeControl("Target chunk size", "KiB", chunkSizeVar, 64, 4096, 64),
          rangeControl("Expected duplicate input", "%", duplicateVar, 0, 95, 1),
        ),
      ),
      sectionTag(
        cls := "mission-section",
        headerSection("Arithmetic estimate"),
        p(
          cls := "page-intro",
          "These values are deterministic arithmetic over the selected inputs. They are not measured throughput, latency, or capacity guarantees.",
        ),
        child <-- estimateSignal.map { value =>
          div(
            cls := "mission-metrics-grid",
            renderMetric("Daily input", f"${value.inputTiB}%.2f TiB", "Preset workload volume", "⬇️"),
            renderMetric("Duplicate candidate", f"${value.duplicateTiB}%.2f TiB", "Input × duplicate assumption", "♻️"),
            renderMetric("Estimated unique", f"${value.uniqueTiB}%.2f TiB", "Before replicas or compression", "💾"),
            renderMetric("Approximate blocks", value.approximateBlocks.toString, "Unique bytes ÷ target chunk size", "🧩"),
          )
        },
      ),
      sectionTag(
        cls := "mission-section",
        headerSection("Runnable local proof"),
        div(
          cls := "mission-kit",
          div(
            cls := "mission-config",
            h4("Shell commands"),
            pre(code(child.text <-- commandSignal)),
          ),
          div(
            cls := "mission-steps",
            h4("What this proves"),
            ol(
              li("The CLI persists blocks and a versioned manifest to the selected directory."),
              li("The complete content ID can be used by a fresh JVM for stat, get, and verify."),
              li("The demo script compares the retrieved file with the original byte-for-byte."),
            ),
            div(
              cls := "mission-insights",
              h5("Selected workload assumptions"),
              ul(children <-- workloadVar.signal.map(_.assumptions.map(text => li(text)))),
            ),
          ),
        ),
      ),
      sectionTag(
        cls := "mission-section",
        headerSection("Evidence boundary"),
        div(
          cls := "mission-feed",
          proofCard("Durability", "FsBlobManifestRepoSpec closes and recreates the store before retrieval."),
          proofCard("HTTP contract", "HttpApiSpec covers POST, GET, HEAD, DELETE, malformed IDs, missing blobs, and empty uploads."),
          proofCard("Performance", "No throughput or latency claim is made until a reproducible benchmark is published."),
        ),
      ),
    )

  private def headerSection(title: String): HtmlElement =
    HtmlTag("header")(
      cls := "mission-section-header",
      h3(title),
    )

  private def renderWorkloadCard(workload: Workload, selected: Var[Workload]): HtmlElement =
    div(
      cls := "scenario-card",
      cls("active") <-- selected.signal.map(_.id == workload.id),
      onClick --> { _ => selected.set(workload) },
      div(cls := "scenario-icon", workload.icon),
      h4(workload.title),
      p(cls   := "scenario-summary", workload.summary),
      div(
        cls   := "scenario-meta",
        span(f"${workload.inputTiBPerDay}%.1f TiB/day"),
        span(s"${workload.duplicatePercent}% duplicate assumption"),
      ),
    )

  private def rangeControl(
    labelText: String,
    unit: String,
    state: Var[Int],
    minimum: Int,
    maximum: Int,
    step: Int,
  ): HtmlElement =
    div(
      cls := "range-control-group",
      label(
        span(cls   := "range-label", labelText),
        strong(cls := "range-value", child.text <-- state.signal.map(value => s"$value $unit")),
      ),
      input(
        cls      := "range-control",
        tpe      := "range",
        minAttr  := minimum.toString,
        maxAttr  := maximum.toString,
        stepAttr := step.toString,
        controlled(
          value <-- state.signal.map(_.toString),
          onInput.mapToValue --> Observer[String](raw => raw.toIntOption.foreach(state.set)),
        ),
      ),
    )

  private def renderMetric(title: String, value: String, caption: String, icon: String): HtmlElement =
    div(
      cls := "mission-metric",
      span(cls := "metric-icon", icon),
      div(
        span(cls   := "metric-title", title),
        strong(cls := "metric-value", value),
        small(cls  := "metric-caption", caption),
      ),
    )

  private def statBlock(label: String, value: String, icon: String): HtmlElement =
    div(
      cls := "mission-hero-stat",
      span(cls := "stat-icon", icon),
      div(
        span(cls := "stat-label", label),
        strong(value),
      ),
    )

  private def proofCard(title: String, body: String): HtmlElement =
    div(
      cls := "mission-event tone-info",
      div(cls := "event-icon", "✓"),
      div(
        cls   := "event-body",
        h4(title),
        p(body),
      ),
    )

  private def estimate(plan: CapacityPlan): CapacityEstimate =
    val duplicateFraction = plan.duplicatePercent.toDouble / 100.0
    val duplicateTiB      = plan.workload.inputTiBPerDay * duplicateFraction
    val uniqueTiB         = plan.workload.inputTiBPerDay - duplicateTiB
    val uniqueBytes       = uniqueTiB * 1024.0 * 1024.0 * 1024.0 * 1024.0
    val chunkBytes        = plan.chunkSizeKiB.toDouble * 1024.0

    CapacityEstimate(
      inputTiB = plan.workload.inputTiBPerDay,
      duplicateTiB = duplicateTiB,
      uniqueTiB = uniqueTiB,
      approximateBlocks = math.ceil(uniqueBytes / chunkBytes).toLong,
    )

  private def commands(plan: CapacityPlan): String =
    val chunkBytes = plan.chunkSizeKiB * 1024
    s"""export GRAVITON_DATA_DIR=\"./.graviton\"
       |export GRAVITON_CHUNK_SIZE=\"$chunkBytes\"
       |
       |./sbt \"cli/run ingest /path/to/blob\"
       |
       |# Copy the complete Blob ID printed above, then run:
       |./sbt \"cli/run stat $$BLOB_ID\"
       |./sbt \"cli/run get $$BLOB_ID ./retrieved.bin\"
       |./sbt \"cli/run verify $$BLOB_ID\"
       |
       |# Or exercise the complete restart-safe lifecycle:
       |./scripts/demo-local.sh""".stripMargin
