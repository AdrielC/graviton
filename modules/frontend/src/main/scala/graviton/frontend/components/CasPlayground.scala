package graviton.frontend.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.tags.HtmlTag
import graviton.shared.cas.*

/**
 * Interactive CAS Playground — visualize content-addressed storage in the browser.
 *
 * Imports the cross-compiled `graviton.shared.cas` model:
 *   - `CasSimulator.ingest` — pure simulation using real SHA-256 (`pt.kcry:sha`)
 *   - `CasBlock`, `IngestSummary` — domain types with Iron refined fields
 *   - `BlockSize`, `BlockIndex`, `ByteCount` — Iron opaque types
 *   - `Sha256Hex` — exactly 64 lowercase hex chars, validated by Iron
 *   - `Sha256Cross` — cross-platform SHA-256 (JVM + JS + Native)
 */
object CasPlayground:

  def apply(): HtmlElement =
    val inputTextVar  = Var("Hello, Graviton! Type or paste anything here to see content-addressed chunking in action.")
    val blockSizeVar  = Var(32)
    val resultVar     = Var(Option.empty[(IngestSummary, List[CasBlock])])
    val processingVar = Var(false)
    val modeVar       = Var("text")
    val randomSizeVar = Var(256)
    val historyVar    = Var(Set.empty[Sha256Hex])

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

      val bs = BlockSize.either(math.max(8, blockSizeVar.now())) match
        case Right(v) => v
        case Left(_)  => BlockSize.applyUnsafe(32)

      val (summary, blocks, updatedHistory) =
        CasSimulator.ingest(bytes, bs, historyVar.now())

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
          " — cross-compiled Iron types, real SHA-256 via ",
          code("pt.kcry:sha"),
        ),
      ),
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
          label(child.text <-- blockSizeVar.signal.map(s => s"Block size: $s bytes — BlockSize = Int :| [1, 16 MiB]")),
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
            historyVar.set(Set.empty[Sha256Hex])
            resultVar.set(None)
          },
        ),
        span(
          cls := "cas-pg-history-count",
          child.text <-- historyVar.signal.map(h => s"${h.size} unique blocks in history"),
        ),
      ),
      child <-- resultVar.signal.map {
        case None                    => div()
        case Some((summary, blocks)) => renderResult(summary, blocks)
      },
      div(
        cls       := "cas-pg-types",
        styleAttr := "margin-top: 2rem; padding: 1rem; border: 1px solid rgba(0,255,65,0.12); border-radius: 10px; background: rgba(0,0,0,0.15);",
        h4(styleAttr := "color: var(--vp-c-brand-1, #00ff41); margin: 0 0 0.5rem;", "Iron Types (from graviton.shared.cas)"),
        HtmlTag("table")(
          cls        := "cas-pg-table",
          HtmlTag("thead")(
            HtmlTag("tr")(
              HtmlTag("th")("Type"),
              HtmlTag("th")("Constraint"),
            )
          ),
          HtmlTag("tbody")(
            typeRow("BlockSize", "Int :| GreaterEqual[1] & LessEqual[16777216]"),
            typeRow("BlockIndex", "Int :| GreaterEqual[0]"),
            typeRow("ByteCount", "Long :| GreaterEqual[0L]"),
            typeRow("Sha256Hex", """String :| Match["[0-9a-f]{64}"]"""),
            typeRow("HexDigest", """String :| Match["[0-9a-f]+"] & MinLength[1] & MaxLength[128]"""),
            typeRow("Algo", """String :| Match["(sha-256|sha-1|blake3|md5)"]"""),
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
      div(
        cls := "cas-pg-stats",
        statCard("Total Bytes", summary.totalBytes.toString, "ByteCount"),
        statCard("Blocks", summary.blockCount.toString, "BlockIndex"),
        statCard("Unique", summary.uniqueBlocks.toString, "fresh"),
        statCard("Duplicates", summary.duplicateBlocks.toString, "deduped"),
        statCard("Dedup Ratio", f"${summary.dedupRatio * 100}%.1f%%", "saved"),
        statCard("Blob Digest", summary.blobDigest.take(16) + "...", summary.algo.label),
      ),
      div(
        cls := "cas-pg-blocks",
        h4("Block Map"),
        p(cls := "cas-pg-blocks-hint", "Each cell is a CasBlock. Green = fresh, amber = duplicate. Hover for details."),
        div(
          cls := "cas-pg-block-grid",
          blocks.map { block =>
            div(
              cls   := (if block.isDuplicate then "cas-pg-block duplicate" else "cas-pg-block fresh"),
              title := s"CasBlock(index=${block.index}, size=${block.size}, digest=${block.digest}, isDuplicate=${block.isDuplicate})",
              span(cls := "cas-pg-block-idx", block.index.toString),
            )
          },
        ),
      ),
      div(
        cls := "cas-pg-detail",
        h4("Block Details"),
        HtmlTag("table")(
          cls := "cas-pg-table",
          HtmlTag("thead")(
            HtmlTag("tr")(
              HtmlTag("th")("index"),
              HtmlTag("th")("size"),
              HtmlTag("th")("digest: Sha256Hex"),
              HtmlTag("th")("Status"),
            )
          ),
          HtmlTag("tbody")(
            blocks.take(50).map { block =>
              HtmlTag("tr")(
                cls := (if block.isDuplicate then "dup-row" else "fresh-row"),
                HtmlTag("td")(block.index.toString),
                HtmlTag("td")(s"${block.size} B"),
                HtmlTag("td")(code(cls := "cas-pg-digest", block.shortDigest)),
                HtmlTag("td")(
                  span(
                    cls := (if block.isDuplicate then "cas-pg-badge dup" else "cas-pg-badge fresh"),
                    if block.isDuplicate then "DUP" else "NEW",
                  )
                ),
              )
            },
            (if blocks.length > 50 then List(HtmlTag("tr")(HtmlTag("td")(colSpan := 4, s"... and ${blocks.length - 50} more blocks")))
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

  private def typeRow(name: String, constraint: String): HtmlElement =
    HtmlTag("tr")(
      HtmlTag("td")(code(name)),
      HtmlTag("td")(code(constraint)),
    )

end CasPlayground
