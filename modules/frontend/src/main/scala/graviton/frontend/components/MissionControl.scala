package graviton.frontend.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.tags.HtmlTag
import graviton.frontend.GravitonApi
import graviton.shared.ApiModels.SystemStats
import org.scalajs.dom
import scala.collection.immutable.LazyList
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random
import zio.*

/** Immersive planner that turns the demo into a mission control center. */
object MissionControl:

  private val sectionTag = HtmlTag("section")

  private final case class Scenario(
    id: String,
    title: String,
    summary: String,
    dataset: String,
    icon: String,
    dailyIngestTb: Double,
    changeRatePct: Double,
    dedupPotential: Double,
    compliance: String,
    regions: List[String],
    insights: List[String],
  )

  private enum ChunkerMode(
    val label: String,
    val icon: String,
    val configValue: String,
    val throughputBoost: Double,
    val dedupWeight: Double,
  ):
    case FastCDC     extends ChunkerMode("FastCDC Autopilot", "🌀", "fastcdc", 1.0, 1.0)
    case AnchoredCDC extends ChunkerMode("Anchored CDC", "🛰️", "anchored-cdc", 0.9, 1.25)
    case Fixed       extends ChunkerMode("Fixed 1 MiB", "🧊", "fixed-1mib", 1.12, 0.7)

  private final case class MissionPlan(
    scenario: Scenario,
    chunker: ChunkerMode,
    chunkSizeKb: Int,
    replicas: Int,
    autopilot: Boolean,
    encryption: Boolean,
    streaming: Boolean,
  )

  private final case class MissionMetrics(
    ingestMbPerSec: Double,
    dedupSavingsPercent: Double,
    durabilitySeconds: Double,
    manifestReadyMs: Double,
    carbonSavingsKg: Double,
  )

  private enum Tone:
    case Info, Advisory, Critical

  private final case class MissionEvent(icon: String, title: String, body: String, tone: Tone)

  private val scenarios = List(
    Scenario(
      id = "observatory-firehose",
      title = "Observatory Firehose",
      summary = "Streaming multispectral captures from orbital telescopes with erratic burst windows.",
      dataset = "4.8 TB/day of sensor fusion frames + telemetry",
      icon = "🔭",
      dailyIngestTb = 4.8,
      changeRatePct = 0.62,
      dedupPotential = 0.38,
      compliance = "FedRAMP High",
      regions = List("us-west-2", "eu-central-1", "ap-south-1"),
      insights = List(
        "Burst windows spike above 35 Gbps whenever solar flare captures land.",
        "Prefer anchored CDC when mirrors drift — boundaries stay stable despite chaos.",
        "Archive cold stripes to S3 Glacier Instant for cheap planetary-scale retention.",
      ),
    ),
    Scenario(
      id = "genomics-quorum",
      title = "Genomics Quorum",
      summary = "Population-scale DNA panels with intense similarity budgets and zero tolerance for drift.",
      dataset = "7.2 TB/day of FASTQ + variant graphs",
      icon = "🧬",
      dailyIngestTb = 7.2,
      changeRatePct = 0.41,
      dedupPotential = 0.74,
      compliance = "HIPAA + GDPR",
      regions = List("us-east-1", "eu-west-1", "ca-central-1"),
      insights = List(
        "Shared fragment rate averages 68% — perfect FastCDC playground.",
        "Dual replication is mandatory; third replica to EU keeps auditors relaxed.",
        "Inline encryption must stay enabled; clinical payloads never rest in plaintext.",
      ),
    ),
    Scenario(
      id = "observability-edge",
      title = "Observability Edge Mesh",
      summary = "Planetary fleet of edge functions streaming traces, profiles, and crash snapshots nonstop.",
      dataset = "2.1 TB/day across 18 regions",
      icon = "⚡",
      dailyIngestTb = 2.1,
      changeRatePct = 0.93,
      dedupPotential = 0.27,
      compliance = "SOC 2 + PCI",
      regions = List("us-east-2", "sa-east-1", "ap-northeast-1"),
      insights = List(
        "Latency budget is king; prefer fixed chunk windows to keep CPU caches happy.",
        "Edge encryption adds ~6% tax but unifies PCI posture globally.",
        "Streaming mode lets you mirror the fleet into observability tables in <40s.",
      ),
    ),
  )

  def apply(api: GravitonApi): HtmlElement =
    val scenarioVar   = Var(scenarios.head)
    val chunkerVar    = Var(ChunkerMode.FastCDC)
    val chunkSizeVar  = Var(384)
    val replicasVar   = Var(3)
    val autopilotVar  = Var(true)
    val encryptionVar = Var(true)
    val streamingVar  = Var(true)
    val tickerVar     = Var(true)
    val statsVar      = Var[Option[SystemStats]](None)
    val feedVar       = Var(
      List(MissionEvent("🚀", "Mission Control primed", "Dial in a scenario and watch the planner respond in real time.", Tone.Info))
    )
    val runtime       = Runtime.default
    val random        = new Random()

    def loadStats(): Unit =
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.getStats).onComplete {
          case scala.util.Success(stats) =>
            statsVar.set(Some(stats))
          case scala.util.Failure(error) =>
            dom.console.warn("MissionControl stats fallback", error.getMessage)
        }
      }

    def currentPlan(): MissionPlan =
      MissionPlan(
        scenarioVar.now(),
        chunkerVar.now(),
        chunkSizeVar.now(),
        replicasVar.now(),
        autopilotVar.now(),
        encryptionVar.now(),
        streamingVar.now(),
      )

    val planVar = Var(currentPlan())

    def recomputePlan(): Unit = planVar.set(currentPlan())

    List(
      scenarioVar.signal,
      chunkerVar.signal,
      chunkSizeVar.signal,
      replicasVar.signal,
      autopilotVar.signal,
      encryptionVar.signal,
      streamingVar.signal,
    ).foreach(_.changes.foreach(_ => recomputePlan())(using unsafeWindowOwner))

    val planSignal: Signal[MissionPlan] = planVar.signal

    val metricsSignal: Signal[MissionMetrics] =
      planSignal
        .combineWith(statsVar.signal)
        .map((plan, stats) => computeMetrics(plan, stats))

    val configSignal = planSignal.map(missionConfig)
    val stepsSignal  = planSignal.map(launchSteps)

    val ticker: EventStream[Unit] =
      tickerVar.signal.flatMapSwitch {
        case true  => EventStream.periodic(4200).mapTo(())
        case false => EventStream.empty
      }

    val narrativeStream: EventStream[(MissionPlan, MissionMetrics)] =
      ticker.map { _ =>
        val plan    = MissionPlan(
          scenarioVar.now(),
          chunkerVar.now(),
          chunkSizeVar.now(),
          replicasVar.now(),
          autopilotVar.now(),
          encryptionVar.now(),
          streamingVar.now(),
        )
        val metrics = computeMetrics(plan, statsVar.now())
        (plan, metrics)
      }

    narrativeStream.foreach { case (plan, metrics) =>
      val event = synthesizeEvent(random, plan, metrics)
      feedVar.update(events => (event :: events).take(6))
    }(using unsafeWindowOwner)

    div(
      cls := "mission-control",
      onMountUnmountCallback(
        mount = _ => {
          loadStats()
          tickerVar.set(true)
        },
        unmount = _ => tickerVar.set(false),
      ),
      div(
        cls := "mission-hero",
        div(
          cls := "mission-hero-copy",
          h2("🛰️ Mission Control Studio"),
          p(
            cls := "page-intro",
            "Prototype entire ingest strategies, mix chunkers, dial replication, and export ready-to-run configs straight from the docs site.",
          ),
          div(
            cls := "mission-hero-badges",
            span("Adaptive chunk orchestration"),
            span("Live-ready deployment kit"),
            span("Dedup intelligence"),
          ),
        ),
        child <-- statsVar.signal.map {
          case Some(stats) =>
            div(
              cls := "mission-hero-stats",
              statBlock("Connected cluster", stats.totalBlobs.toString, "📦"),
              statBlock("Unique chunks", stats.uniqueChunks.toString, "🧩"),
              statBlock("Dedup ratio", f"${stats.deduplicationRatio}%.2f:1", "📈"),
            )
          case None        =>
            div(
              cls := "mission-hero-stats offline",
              statBlock("Demo dataset", "Offline mode", "🛰️"),
              statBlock("Simulated chunks", "9", "🧪"),
              statBlock("Replayable plan", "Yes", "✅"),
            )
        },
      ),
      sectionTag(
        cls := "mission-section",
        headerSection("Choose a scenario"),
        div(
          cls := "scenario-grid",
          scenarios.map(s => renderScenarioCard(s, scenarioVar)),
        ),
      ),
      sectionTag(
        cls := "mission-section",
        headerSection("Orchestrate the pipeline"),
        div(
          cls := "mission-controls",
          div(
            cls := "chunker-modes",
            ChunkerMode.values.toList.map(mode => renderChunkerChip(mode, chunkerVar)),
          ),
          div(
            cls := "range-control-group",
            label(
              span(cls   := "range-label", "Target chunk size"),
              strong(cls := "range-value", child.text <-- chunkSizeVar.signal.map(size => s"$size KiB")),
            ),
            input(
              cls      := "range-control",
              tpe      := "range",
              minAttr  := "64",
              maxAttr  := "1024",
              stepAttr := "32",
              controlled(
                value <-- chunkSizeVar.signal.map(_.toString),
                onInput.mapToValue --> Observer[String](raw => raw.toIntOption.foreach(chunkSizeVar.set)),
              ),
            ),
          ),
          div(
            cls := "range-control-group",
            label(
              span(cls   := "range-label", "Replication factor"),
              strong(cls := "range-value", child.text <-- replicasVar.signal.map(_.toString)),
            ),
            input(
              cls      := "range-control",
              tpe      := "range",
              minAttr  := "1",
              maxAttr  := "5",
              stepAttr := "1",
              controlled(
                value <-- replicasVar.signal.map(_.toString),
                onInput.mapToValue --> Observer[String](raw => raw.toIntOption.foreach(value => replicasVar.set(value))),
              ),
            ),
          ),
          div(
            cls := "toggle-group",
            toggleControl("Autopilot tuning", autopilotVar),
            toggleControl("Inline encryption", encryptionVar),
            toggleControl("Streaming commits", streamingVar),
          ),
        ),
      ),
      sectionTag(
        cls := "mission-section",
        headerSection("Projected impact"),
        child <-- metricsSignal.map { metrics =>
          div(
            cls := "mission-metrics-grid",
            renderMetric(
              "Projected ingest",
              f"${metrics.ingestMbPerSec}%.0f MB/s",
              "Synthesized from scenario scale + chunker profile",
              "🚀",
            ),
            renderMetric("Dedup savings", f"${metrics.dedupSavingsPercent}%.1f%%", "Estimated elimination of redundant payloads", "🧠"),
            renderMetric("Durability reach", f"${metrics.durabilitySeconds}%.0f s", "Time to reach durable multi-region copies", "🛡️"),
            renderMetric("Manifest ready", f"${metrics.manifestReadyMs}%.0f ms", "When manifests are emitted to clients", "📜"),
            renderMetric("Carbon avoided", f"${metrics.carbonSavingsKg}%.1f kg", "Energy saved vs naive 3x replication", "🌱"),
          )
        },
      ),
      sectionTag(
        cls := "mission-section",
        headerSection("Deployment kit"),
        div(
          cls := "mission-kit",
          div(
            cls := "mission-config",
            h4("Generated config"),
            pre(code(child.text <-- configSignal)),
          ),
          div(
            cls := "mission-steps",
            h4("Launch checklist"),
            ol(children <-- stepsSignal.map(_.map(step => li(step)))),
            div(
              cls := "mission-insights",
              h5("Scenario intelligence"),
              ul(children <-- scenarioVar.signal.map(_.insights.map(text => li(text)))),
            ),
          ),
        ),
      ),
      sectionTag(
        cls := "mission-section",
        headerSection("Live mission feed"),
        div(
          cls := "mission-feed-controls",
          button(
            cls := "btn-secondary",
            child.text <-- tickerVar.signal.map(active => if active then "Pause narrative" else "Resume narrative"),
            onClick --> { _ => tickerVar.update(value => !value) },
          ),
          button(
            cls := "btn-primary",
            "Inject insight",
            onClick --> { _ =>
              val plan    = MissionPlan(
                scenarioVar.now(),
                chunkerVar.now(),
                chunkSizeVar.now(),
                replicasVar.now(),
                autopilotVar.now(),
                encryptionVar.now(),
                streamingVar.now(),
              )
              val metrics = computeMetrics(plan, statsVar.now())
              feedVar.update(events => (synthesizeEvent(random, plan, metrics) :: events).take(6))
            },
          ),
        ),
        div(
          cls := "mission-feed",
          children <-- feedVar.signal.map(_.map(renderEventCard)),
        ),
      ),
    )

  private def headerSection(title: String): HtmlElement =
    HtmlTag("header")(
      cls := "mission-section-header",
      h3(title),
    )

  private def renderScenarioCard(scenario: Scenario, scenarioVar: Var[Scenario]): HtmlElement =
    div(
      cls := "scenario-card",
      cls("active") <-- scenarioVar.signal.map(_.id == scenario.id),
      onClick --> { _ => scenarioVar.set(scenario) },
      div(cls := "scenario-icon", scenario.icon),
      h4(scenario.title),
      p(cls   := "scenario-summary", scenario.summary),
      div(
        cls   := "scenario-meta",
        span(scenario.dataset),
        span(s"Change rate ${scenario.changeRatePct * 100}%.0f%%"),
        span(scenario.compliance),
      ),
      div(
        cls   := "scenario-regions",
        scenario.regions.map(region => span(region)),
      ),
    )

  private def renderChunkerChip(mode: ChunkerMode, chunkerVar: Var[ChunkerMode]): HtmlElement =
    button(
      cls := "chunker-chip",
      cls("active") <-- chunkerVar.signal.map(_ == mode),
      span(cls := "chip-icon", mode.icon),
      div(
        span(mode.label),
        small(mode match
          case ChunkerMode.FastCDC     => "Balanced dedup × throughput"
          case ChunkerMode.AnchoredCDC => "Stable anchors for noisy datasets"
          case ChunkerMode.Fixed       => "Deterministic 1 MiB slices"),
      ),
      onClick --> { _ => chunkerVar.set(mode) },
    )

  private def toggleControl(labelText: String, signal: Var[Boolean]): HtmlElement =
    label(
      cls := "toggle-control",
      span(labelText),
      input(
        tpe := "checkbox",
        checked <-- signal.signal,
        onInput.mapToChecked --> signal.writer,
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

  private def renderEventCard(event: MissionEvent): HtmlElement =
    val toneClass = event.tone match
      case Tone.Info     => "tone-info"
      case Tone.Advisory => "tone-advisory"
      case Tone.Critical => "tone-critical"

    div(
      cls := s"mission-event $toneClass",
      div(cls := "event-icon", event.icon),
      div(
        cls   := "event-body",
        h4(event.title),
        p(event.body),
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

  private def computeMetrics(plan: MissionPlan, stats: Option[SystemStats]): MissionMetrics =
    val baseThroughput  = (plan.scenario.dailyIngestTb * 1048576.0) / 86400.0
    val liveFactor      =
      stats match
        case Some(value) if value.totalBlobs > 0 =>
          0.8 + math.log10(value.totalBlobs.toDouble.max(1.0))
        case _                                   => 1.0
    val chunkSizeFactor = 384.0 / plan.chunkSizeKb.toDouble
    val chunkerFactor   = plan.chunker.throughputBoost
    val autopilotFactor = if plan.autopilot then 1.12 else 0.95
    val encryptionTax   = if plan.encryption then 0.93 else 1.0
    val streamingBoost  = if plan.streaming then 1.07 else 0.98
    val replicaFactor   = 0.82 + (plan.replicas * 0.11)

    val ingestMb =
      (baseThroughput * liveFactor * chunkerFactor * autopilotFactor * streamingBoost * encryptionTax * replicaFactor)
        .max(65.0)
        .min(4200.0)

    val dedup =
      val base       = plan.scenario.dedupPotential * 100.0
      val bias       = plan.chunker.dedupWeight * 8.0 * chunkSizeFactor
      val guardrails =
        if plan.chunkSizeKb < 192 then 4.0 else if plan.chunkSizeKb > 768 then -3.0 else 0.0
      (base + bias + guardrails).max(18.0).min(92.0)

    val durability =
      val streamingBonus = if plan.streaming then 1.35 else 1.0
      val autopilotBonus = if plan.autopilot then 1.1 else 0.85
      (280.0 / (plan.replicas * streamingBonus * autopilotBonus)).max(12.0)

    val manifestReady =
      val base      = 45.0 + (plan.chunkSizeKb / 2.0)
      val anchorTax =
        plan.chunker match
          case ChunkerMode.AnchoredCDC => 28.0
          case ChunkerMode.Fixed       => 12.0
          case ChunkerMode.FastCDC     => 0.0
      (base + anchorTax).min(420.0)

    val carbonSavings =
      val streamOffset   = if plan.streaming then 0.82 else 1.0
      val encryptPenalty = if plan.encryption then 1.05 else 0.9
      val replicaSpread  = plan.replicas match
        case r if r >= 4 => 0.9
        case 3           => 1.0
        case 2           => 1.2
        case _           => 1.5
      val base           = plan.scenario.dailyIngestTb * 9.5
      (base * streamOffset / replicaSpread) * encryptPenalty

    MissionMetrics(
      ingestMbPerSec = math.round(ingestMb).toDouble,
      dedupSavingsPercent = dedup,
      durabilitySeconds = durability,
      manifestReadyMs = manifestReady,
      carbonSavingsKg = carbonSavings,
    )

  private def missionConfig(plan: MissionPlan): String =
    val regions =
      if plan.replicas <= plan.scenario.regions.length then plan.scenario.regions.take(plan.replicas)
      else LazyList.continually(plan.scenario.regions).flatten.take(plan.replicas).toList

    val replicaBlock =
      regions.zipWithIndex
        .map { case (region, idx) =>
          val backend = if idx == 0 then "filesystem" else "s3"
          s"""    {
             |      id = "replica-${idx + 1}"
             |      backend = "$backend"
             |      region = "$region"
             |    }""".stripMargin
        }
        .mkString("\n")

    s"""graviton {
       |  mission-id = "${plan.scenario.id}"
       |  chunking {
       |    mode = "${plan.chunker.configValue}"
       |    target-kib = ${plan.chunkSizeKb}
       |    autopilot = ${plan.autopilot}
       |  }
       |  streaming {
       |    enabled = ${plan.streaming}
       |    manifest-emission-ms = ${math.round(45 + plan.chunkSizeKb / 2.0)}
       |  }
       |  security {
       |    encryption = ${plan.encryption}
       |  }
       |  replication {
       |    copies = ${plan.replicas}
       |    targets = [
       |$replicaBlock
       |    ]
       |  }
       |}""".stripMargin

  private def launchSteps(plan: MissionPlan): List[String] =
    val autopilotStep =
      if plan.autopilot then "Enable chunker autopilot: export GRAVITON_CHUNK_AUTOPILOT=on"
      else "Lock chunker window manually: export GRAVITON_CHUNK_AUTOPILOT=off"

    val chunkCommand =
      s"""./sbt "cli/run ingest --scenario ${plan.scenario.id} --chunker ${plan.chunker.configValue} --target-kib ${plan.chunkSizeKb} --replicas ${plan.replicas}""""

    val streamStep =
      if plan.streaming then "Mirror commits via WebSocket: graviton ctl stream --topic manifests/live"
      else "Schedule batch publish: graviton ctl publish --interval 60s"

    val encryptionStep =
      if plan.encryption then "Verify kms status: graviton ctl kms status --expect-enabled"
      else "⚠️ Inline encryption disabled; restrict access to storage tier."

    List(
      autopilotStep,
      chunkCommand,
      streamStep,
      encryptionStep,
    )

  private def synthesizeEvent(random: Random, plan: MissionPlan, metrics: MissionMetrics): MissionEvent =
    val palette = List(
      ("FastCDC autopilot narrowed bounds", Tone.Info, "🌀"),
      ("Anchored CDC locked on rolling hash peak", Tone.Info, "🛰️"),
      ("Fixed window optimized for cache locality", Tone.Info, "🧊"),
      ("Replica lag breached threshold; repair job dispatched", Tone.Advisory, "🛠️"),
      ("Inline encryption rotated keys mid-flight", Tone.Info, "🔐"),
      ("Streaming lane throttled to protect cold tier", Tone.Advisory, "🚦"),
      ("Quarantine triggered for mismatched manifest hash", Tone.Critical, "🛡️"),
    )

    val (title, tone, icon) = palette(random.nextInt(palette.length))

    val body = title match
      case t if t.startsWith("FastCDC")           =>
        f"Dedup now tracking ${metrics.dedupSavingsPercent}%.1f%% savings while ${plan.chunkSizeKb} KiB slices stay under ${metrics.manifestReadyMs}%.0f ms."
      case t if t.startsWith("Anchored")          =>
        f"Anchors stabilized ${plan.scenario.title}; durability now ${metrics.durabilitySeconds}%.0f s for ${plan.replicas} replicas."
      case t if t.startsWith("Fixed")             =>
        f"Cache hit ratio improved; ingest sustaining ${metrics.ingestMbPerSec}%.0f MB/s without jitter."
      case t if t.startsWith("Replica lag")       =>
        s"Replica ${plan.replicas} in ${plan.scenario.regions.lastOption.getOrElse("unknown")} promoted to catch up."
      case t if t.startsWith("Inline encryption") =>
        if plan.encryption then "AES-GCM pipeline validated; manifests shipped with fresh AAD tags."
        else "Encryption disabled → autopilot recommending enabling it before production."
      case t if t.startsWith("Streaming lane")    =>
        if plan.streaming then "Live stream paused for 2.4s to let NVMe cache drain. No client impact detected."
        else "Batch mode cannot throttle on its own — consider enabling streaming commits."
      case _                                      =>
        "Manifest validation paused one chunk, but reheated via repair queue."

    MissionEvent(icon, title, body, tone)
