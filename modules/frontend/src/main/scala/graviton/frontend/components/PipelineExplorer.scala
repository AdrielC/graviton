package graviton.frontend.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.tags.HtmlTag
import graviton.shared.pipeline.*
import org.scalajs.dom

/**
 * Interactive Pipeline Explorer built in Scala.js using Laminar.
 *
 * This component uses the '''same shared model''' (`graviton.shared.pipeline.PipelineCatalog`)
 * that the JVM-side Graviton runtime references, ensuring the stage definitions, summary
 * fields, type signatures, and composition operators shown here are always in sync with
 * the actual `Transducer` algebra code.
 *
 * The Vue `PipelinePlayground` component in the docs theme provides the same experience
 * for visitors who haven't built the Scala.js bundle; this Scala.js version is the
 * "source of truth" because it imports the cross-compiled pipeline descriptors directly.
 */
object PipelineExplorer:

  private val catalog = PipelineCatalog

  def apply(): HtmlElement =
    val selectedPipelineVar = Var(catalog.basicIngest)
    val enabledStageIds     = Var(catalog.basicIngest.stages.map(_.id).toSet)
    val runningVar          = Var(false)
    val tickVar             = Var(0)
    val speedVar            = Var(3)

    // Simulated metrics state
    val totalBytesVar     = Var(0L)
    val blockCountVar     = Var(0L)
    val uniqueCountVar    = Var(0L)
    val duplicateCountVar = Var(0L)
    val digestProgressVar = Var(0L)

    var animHandle: Option[Int] = None

    def startSim(): Unit =
      stopSim()
      runningVar.set(true)
      val handle = dom.window.setInterval(
        () => {
          val spd       = speedVar.now()
          val chunkSize = 65536L * spd
          totalBytesVar.update(_ + chunkSize)
          digestProgressVar.update(_ + chunkSize)
          tickVar.update(_ + 1)

          if totalBytesVar.now() % (2L * 1048576L) < chunkSize then
            blockCountVar.update(_ + 1)
            if enabledStageIds.now().contains("dedup") then
              if scala.util.Random.nextDouble() > 0.25 then uniqueCountVar.update(_ + 1)
              else duplicateCountVar.update(_ + 1)
        },
        80,
      )
      animHandle = Some(handle)

    def stopSim(): Unit =
      animHandle.foreach(dom.window.clearInterval)
      animHandle = None
      runningVar.set(false)

    def resetSim(): Unit =
      stopSim()
      totalBytesVar.set(0L)
      blockCountVar.set(0L)
      uniqueCountVar.set(0L)
      duplicateCountVar.set(0L)
      digestProgressVar.set(0L)
      tickVar.set(0)

    def selectPipeline(p: PipelineDescriptor): Unit =
      resetSim()
      selectedPipelineVar.set(p)
      enabledStageIds.set(p.stages.map(_.id).toSet)

    div(
      cls := "pipeline-playground",
      onUnmountCallback(_ => stopSim()),

      // Header
      HtmlTag("header")(
        cls := "pp-header",
        div(
          cls := "pp-header__info",
          h3(cls := "pp-header__title", "Transducer Pipeline Explorer"),
          p(
            cls  := "pp-header__subtitle",
            "Powered by ",
            code("graviton.shared.pipeline.PipelineCatalog"),
            " — the same model used by the JVM runtime",
          ),
        ),
        div(
          cls := "pp-header__controls",
          button(
            cls <-- runningVar.signal.map(r => if r then "pp-btn active" else "pp-btn"),
            child.text <-- runningVar.signal.map(r => if r then "PAUSE" else "RUN PIPELINE"),
            onClick --> { _ => if runningVar.now() then stopSim() else startSim() },
          ),
          button(
            cls := "pp-btn pp-btn--ghost",
            "RESET",
            onClick --> { _ => resetSim() },
          ),
        ),
      ),

      // Scenario selector
      div(
        cls := "pp-scenarios",
        p(cls := "pp-scenarios__label", "Scenarios (from PipelineCatalog)"),
        div(
          cls := "pp-scenarios__grid",
          catalog.allPipelines.map { p =>
            button(
              cls <-- selectedPipelineVar.signal.map(sel => if sel.name == p.name then "pp-scenario active" else "pp-scenario"),
              span(cls             := "pp-scenario__icon", p.name.take(3).toUpperCase),
              span(cls             := "pp-scenario__name", p.name),
              HtmlTag("small")(cls := "pp-scenario__desc", p.scalaExpression),
              onClick --> { _ => selectPipeline(p) },
            )
          },
        ),
      ),

      // Pipeline visualization
      div(
        cls := "pp-pipeline",

        // Scala expression
        div(
          cls := "pp-expression",
          code(
            cls := "pp-expression__code",
            child.text <-- selectedPipelineVar.signal.map(_.scalaExpression),
          ),
        ),

        // Stages
        div(
          cls := "pp-stages",
          children <-- selectedPipelineVar.signal.map { pipeline =>
            pipeline.stages.zipWithIndex.flatMap { case (stage, idx) =>
              val connector =
                if idx > 0 then
                  val op      = pipeline.operators(idx - 1)
                  val opLabel = op match
                    case CompositionOp.Sequential => ">>>"
                    case CompositionOp.Fanout     => "&&&"
                  List(
                    div(
                      cls := "pp-connector",
                      span(cls := "pp-connector__op", opLabel),
                      div(cls  := "pp-connector__line"),
                    )
                  )
                else Nil

              val stageCard = div(
                cls := "pp-stage-wrapper",
                connector.map(c => c),
                div(
                  cls       := "pp-stage",
                  styleAttr := s"--stage-hue: ${120 + idx * 30}",
                  div(
                    cls       := "pp-stage__header",
                    span(cls := "pp-stage__icon", stage.name.take(1)),
                    span(cls := "pp-stage__name", stage.name),
                  ),
                  div(cls     := "pp-stage__type", code(s"${stage.inputType} => ${stage.outputType}")),
                  p(
                    styleAttr := "margin: 0.3rem 0 0; font-size: 0.75rem; color: var(--vp-c-text-3, #808080);",
                    stage.hotStateDescription,
                  ),
                  // Live metrics
                  div(
                    cls       := "pp-stage__metrics",
                    stage.summaryFields.map { f =>
                      div(
                        cls := "pp-stage__metric",
                        span(cls := "pp-stage__metric-label", f.name),
                        span(
                          cls    := "pp-stage__metric-value",
                          child.text <-- metricSignal(
                            f.name,
                            totalBytesVar,
                            blockCountVar,
                            uniqueCountVar,
                            duplicateCountVar,
                            digestProgressVar,
                          ),
                        ),
                      )
                    },
                  ),
                  // Throughput bar
                  div(
                    cls       := "pp-stage__throughput",
                    div(
                      cls := "pp-stage__throughput-bar",
                      div(
                        cls := "pp-stage__throughput-fill",
                        width <-- tickVar.signal.map { t =>
                          val pct = 40 + Math.sin(t * 0.1 + idx) * 30 + Math.random() * 20
                          s"${Math.min(100, Math.max(0, pct)).toInt}%"
                        },
                      ),
                    ),
                    span(
                      cls := "pp-stage__throughput-label",
                      child.text <-- runningVar.signal.map(r => if r then "streaming" else "idle"),
                    ),
                  ),
                ),
              )

              List(stageCard)
            }
          },
        ),
      ),

      // Summary output
      div(
        cls       := "pp-output",
        styleAttr := "margin-top: 1rem;",
        div(cls := "pp-output__icon", "RECORD SUMMARY"),
        div(
          cls   := "pp-output__fields",
          children <-- selectedPipelineVar.signal.map { pipeline =>
            pipeline.allSummaryFields.map { f =>
              div(
                cls := "pp-output__field",
                span(cls := "pp-output__field-name", f.name),
                span(
                  cls    := "pp-output__field-value",
                  child.text <-- metricSignal(f.name, totalBytesVar, blockCountVar, uniqueCountVar, duplicateCountVar, digestProgressVar),
                ),
                span(cls := "pp-output__field-type", f.scalaType),
              )
            }
          },
        ),
      ),

      // Stage details
      div(
        styleAttr := "margin-top: 1.5rem;",
        h4(styleAttr := "color: var(--vp-c-brand-1, #00ff41); margin: 0 0 0.75rem;", "Stage Details"),
        children <-- selectedPipelineVar.signal.map { pipeline =>
          pipeline.stages.map { stage =>
            div(
              styleAttr := "padding: 0.75rem; margin-bottom: 0.5rem; border: 1px solid rgba(0,255,65,0.12); border-radius: 10px; background: rgba(0,0,0,0.2);",
              strong(styleAttr := "color: var(--vp-c-brand-1, #00ff41);", stage.name),
              p(styleAttr      := "margin: 0.25rem 0 0; font-size: 0.85rem; color: var(--vp-c-text-2, #b3b3b3);", stage.description),
            )
          }
        },
      ),

      // Speed control
      div(
        cls       := "pp-dataflow__speed",
        styleAttr := "margin-top: 1rem;",
        label("Speed: "),
        input(
          typ     := "range",
          minAttr := "1",
          maxAttr := "10",
          controlled(
            value <-- speedVar.signal.map(_.toString),
            onInput.mapToValue.map(_.toInt) --> speedVar,
          ),
          cls     := "pp-dataflow__slider",
        ),
        child.text <-- speedVar.signal.map(s => s"${s}x"),
      ),
    )
  end apply

  private def metricSignal(
    fieldName: String,
    totalBytesVar: Var[Long],
    blockCountVar: Var[Long],
    uniqueCountVar: Var[Long],
    duplicateCountVar: Var[Long],
    digestProgressVar: Var[Long],
  ): Signal[String] =
    fieldName match
      case "totalBytes" | "hashBytes" | "totalSeen" | "compressedBytes" =>
        totalBytesVar.signal.map(formatBytesS)
      case "blockCount" | "blocksKeyed" | "entries"                     =>
        blockCountVar.signal.map(_.toString)
      case "rechunkFill"                                                =>
        blockCountVar.signal.map(c => s"${(c % 100).toInt}%")
      case "uniqueCount" | "verified"                                   =>
        uniqueCountVar.signal.map(_.toString)
      case "duplicateCount" | "failed"                                  =>
        duplicateCountVar.signal.map(_.toString)
      case "digestHex"                                                  =>
        digestProgressVar.signal.map { p =>
          if p > 0 then hashHexS(p).take(16) + "..." else "---"
        }
      case "ratio"                                                      =>
        totalBytesVar.signal.map { tb =>
          if tb > 0 then f"${tb.toDouble / Math.max(1, (tb * 0.62).toLong).toDouble}%.2f" else "---"
        }
      case "rejected"                                                   =>
        totalBytesVar.signal.map(tb => if tb > 10_000_000_000L then "true" else "false")
      case "manifestSize"                                               =>
        blockCountVar.signal.map(c => formatBytesS(c * 48))
      case _                                                            =>
        Val("---")

  private def formatBytesS(n: Long): String =
    if n < 1024L then s"$n B"
    else if n < 1048576L then f"${n / 1024.0}%.1f KB"
    else if n < 1073741824L then f"${n / 1048576.0}%.1f MB"
    else f"${n / 1073741824.0}%.2f GB"

  private def hashHexS(seed: Long): String =
    var h  = (seed & 0xffffffffL).toInt
    val sb = new StringBuilder(32)
    (0 until 32).foreach { i =>
      h = ((h << 5) - h + (i * 7 + 13))
      sb.append(Integer.toHexString((h >>> 0) & 0xf))
    }
    sb.result()

end PipelineExplorer
