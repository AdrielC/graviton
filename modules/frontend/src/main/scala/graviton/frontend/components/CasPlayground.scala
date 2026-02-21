package graviton.frontend.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.tags.HtmlTag
import graviton.shared.cas.*

/**
 * Interactive CAS Playground — visualize content-addressed storage in the browser.
 *
 * Uses the cross-compiled `graviton.shared.cas` model with Iron refined types:
 *   - `BlockSize` (bounded [1, 16 MiB])
 *   - `HexDigest` (hex-constrained strings)
 *   - `AlgoName` (constrained to known algorithms)
 *   - `CasBlock`, `IngestSummary` (shared domain types)
 *   - `CasSimulator.ingest` (pure simulation, same structure as JVM pipeline)
 *
 * The simulation mirrors `IngestPipeline.rechunk >>> CasIngest.blockKeyDeriver`:
 * fixed-size chunking, per-block hashing, dedup tracking.
 */
object CasPlayground:

  def apply(): HtmlElement =
    val inputTextVar  = Var("Hello, Graviton! Type or paste anything here to see content-addressed chunking in action.")
    val blockSizeVar  = Var(32)
    val resultVar     = Var(Option.empty[(IngestSummary, List[CasBlock])])
    val processingVar = Var(false)
    val modeVar       = Var("text") // "text" | "random" | "duplicate"
    val randomSizeVar = Var(256)
    val historyVar    = Var(Set.empty[HexDigest])

    def processInput(): Unit =
      val bytes = modeVar.now() match
        case "random"    =>
          val arr = new Array[Byte](randomSizeVar.now())
          val rng = new scala.util.Random()
          rng.nextBytes(arr)
          arr
        case "duplicate" =>
          val base = inputTextVar.now().getBytes("UTF-8")
          base ++ base ++ base
        case _           =>
          inputTextVar.now().getBytes("UTF-8")

      if bytes.isEmpty then
        resultVar.set(None)
        return

      processingVar.set(true)

      val bs = CasTypes.blockSizeUnsafe(math.max(8, blockSizeVar.now()))

      val (summary, blocks, updatedHistory) =
        CasSimulator.ingest(bytes, bs, HashAlgoDescriptor.Sha256Sim, historyVar.now())

      historyVar.set(updatedHistory)
      resultVar.set(Some((summary, blocks)))
      processingVar.set(false)

    div(
      cls := "cas-playground",
      div(
        cls       := "cas-pg-header",
        h2("CAS Playground"),
        p(
          cls := "cas-pg-subtitle",
          "Powered by ",
          code("graviton.shared.cas.CasSimulator"),
          " — the same model cross-compiled to JVM and Scala.js",
        ),
      ),

      // Controls
      div(
        cls       := "cas-pg-controls",
        div(
          cls := "cas-pg-mode",
          label("Mode: "),
          modeButton(modeVar, "text", "Text Input"),
          modeButton(modeVar, "random", "Random Data"),
          modeButton(modeVar, "duplicate", "Duplicate Test"),
        ),
        div(
          cls := "cas-pg-chunk-size",
          label(child.text <-- blockSizeVar.signal.map(s => s"Block size: $s bytes (Iron: BlockSizeR = Int :| GreaterEqual[1])")),
          input(
            typ      := "range",
            minAttr  := "8",
            maxAttr  := "256",
            stepAttr := "8",
            cls      := "cas-pg-slider",
            controlled(
              value <-- blockSizeVar.signal.map(_.toString),
              onInput.mapToValue.map(_.toInt) --> blockSizeVar,
            ),
          ),
        ),
        child <-- modeVar.signal.map {
          case "random" =>
            div(
              cls := "cas-pg-random-size",
              label(child.text <-- randomSizeVar.signal.map(s => s"Random size: $s bytes")),
              input(
                typ      := "range",
                minAttr  := "32",
                maxAttr  := "2048",
                stepAttr := "32",
                cls      := "cas-pg-slider",
                controlled(
                  value <-- randomSizeVar.signal.map(_.toString),
                  onInput.mapToValue.map(_.toInt) --> randomSizeVar,
                ),
              ),
            )
          case _        => span()
        },
      ),

      // Input area
      child <-- modeVar.signal.map {
        case "text" | "duplicate" =>
          div(
            cls := "cas-pg-input",
            textArea(
              cls         := "cas-pg-textarea",
              placeholder := "Type or paste text here...",
              rows        := 4,
              controlled(
                value <-- inputTextVar.signal,
                onInput.mapToValue --> inputTextVar,
              ),
            ),
          )
        case _                    =>
          div(cls := "cas-pg-input", p(cls := "cas-pg-random-label", "Random bytes will be generated on each run."))
      },

      // Actions
      div(
        cls       := "cas-pg-actions",
        button(
          cls := "cas-pg-run-btn",
          child.text <-- processingVar.signal.map(p => if p then "Processing..." else "Ingest"),
          disabled <-- processingVar.signal,
          onClick --> { _ => processInput() },
        ),
        button(
          cls := "cas-pg-btn cas-pg-btn--ghost",
          "Clear History",
          onClick --> { _ =>
            historyVar.set(Set.empty)
            resultVar.set(None)
          },
        ),
        span(
          cls := "cas-pg-history-count",
          child.text <-- historyVar.signal.map(h => s"${h.size} unique blocks in history"),
        ),
      ),

      // Results
      child <-- resultVar.signal.map {
        case None                    => div()
        case Some((summary, blocks)) => renderResult(summary, blocks)
      },

      // Type info footer
      div(
        cls       := "cas-pg-types",
        styleAttr := "margin-top: 2rem; padding: 1rem; border: 1px solid rgba(0,255,65,0.12); border-radius: 10px; background: rgba(0,0,0,0.15);",
        h4(styleAttr := "color: var(--vp-c-brand-1, #00ff41); margin: 0 0 0.5rem;", "Iron Refined Types in Use"),
        HtmlTag("table")(
          cls        := "cas-pg-table",
          HtmlTag("thead")(
            HtmlTag("tr")(
              HtmlTag("th")("Type"),
              HtmlTag("th")("Base"),
              HtmlTag("th")("Constraint"),
              HtmlTag("th")("Example"),
            )
          ),
          HtmlTag("tbody")(
            typeRow("BlockSizeR", "Int", "GreaterEqual[1]", "32"),
            typeRow("HexDigest", "case class", "MinLength[1] (value)", "a1b2c3d4e5f6..."),
            typeRow("HashAlgoDescriptor", "enum", "Sha256 | Blake3 | Sha256Sim", "sha-256"),
            typeRow("BlockIndexR", "Int", "GreaterEqual[0]", "0"),
            typeRow("ByteCountR", "Long", "GreaterEqual[0L]", "1024"),
          ),
        ),
      ),
    )

  private def modeButton(modeVar: Var[String], mode: String, label: String): HtmlElement =
    button(
      cls <-- modeVar.signal.map(m => if m == mode then "cas-pg-btn active" else "cas-pg-btn"),
      label,
      onClick --> { _ => modeVar.set(mode) },
    )

  private def renderResult(summary: IngestSummary, blocks: List[CasBlock]): HtmlElement =
    div(
      cls := "cas-pg-result",

      // Stats
      div(
        cls := "cas-pg-stats",
        statCard("Total Bytes", summary.totalBytes.toString, "Long :| ≥ 0"),
        statCard("Blocks", summary.blockCount.toString, "Int :| ≥ 0"),
        statCard("Unique", summary.uniqueBlocks.toString, "fresh"),
        statCard("Duplicates", summary.duplicateBlocks.toString, "deduped"),
        statCard("Dedup Ratio", f"${summary.dedupRatio * 100}%.1f%%", "saved"),
        statCard("Blob Digest", summary.blobDigest.truncated, summary.algo.label),
      ),

      // Block grid
      div(
        cls := "cas-pg-blocks",
        h4("Block Map"),
        p(cls := "cas-pg-blocks-hint", "Each cell is a CasBlock. Green = fresh, amber = duplicate. Hover for Iron-typed details."),
        div(
          cls := "cas-pg-block-grid",
          blocks.map { block =>
            div(
              cls   := (if block.isDuplicate then "cas-pg-block duplicate" else "cas-pg-block fresh"),
              title := s"CasBlock(index=${block.index: Int}, size=${block.size: Int}, digest=${block.digest.value}, isDuplicate=${block.isDuplicate})",
              span(cls := "cas-pg-block-idx", block.index.toString),
            )
          },
        ),
      ),

      // Detail table
      div(
        cls := "cas-pg-detail",
        h4("Block Details"),
        HtmlTag("table")(
          cls := "cas-pg-table",
          HtmlTag("thead")(
            HtmlTag("tr")(
              HtmlTag("th")("index: Int :| ≥0"),
              HtmlTag("th")("size: BlockSize"),
              HtmlTag("th")("digest: HexDigest"),
              HtmlTag("th")("Status"),
              HtmlTag("th")("Preview"),
            )
          ),
          HtmlTag("tbody")(
            blocks.take(50).map { block =>
              HtmlTag("tr")(
                cls := (if block.isDuplicate then "dup-row" else "fresh-row"),
                HtmlTag("td")(block.index.toString),
                HtmlTag("td")(s"${block.size: Int} B"),
                HtmlTag("td")(code(cls := "cas-pg-digest", block.shortDigest)),
                HtmlTag("td")(
                  span(
                    cls := (if block.isDuplicate then "cas-pg-badge dup" else "cas-pg-badge fresh"),
                    if block.isDuplicate then "DUP" else "NEW",
                  )
                ),
                HtmlTag("td")(code(cls := "cas-pg-preview", s"block[${block.index}]")),
              )
            },
            (if blocks.length > 50 then List(HtmlTag("tr")(HtmlTag("td")(colSpan := 5, s"... and ${blocks.length - 50} more blocks")))
             else List.empty[HtmlElement]),
          ),
        ),
      ),
    )

  private def statCard(label: String, value: String, unit: String): HtmlElement =
    div(
      cls := "cas-pg-stat",
      div(cls := "cas-pg-stat-value", value),
      div(cls := "cas-pg-stat-label", label),
      div(cls := "cas-pg-stat-unit", unit),
    )

  private def typeRow(name: String, base: String, constraint: String, example: String): HtmlElement =
    HtmlTag("tr")(
      HtmlTag("td")(code(name)),
      HtmlTag("td")(code(base)),
      HtmlTag("td")(code(constraint)),
      HtmlTag("td")(code(example)),
    )

end CasPlayground
