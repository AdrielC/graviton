package graviton.frontend.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.tags.HtmlTag
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/**
 * Interactive CAS Playground — visualize content-addressed storage in the browser.
 *
 * Users can type text, paste content, or generate random data. The playground
 * shows chunking, hashing, and deduplication in real time using the same
 * algorithms described in the Graviton core library.
 *
 * This is a pure client-side simulation using SHA-256 via the Web Crypto API.
 */
object CasPlayground:

  final case class Block(
    index: Int,
    data: Array[Byte],
    hexDigest: String,
    isDuplicate: Boolean,
  ):
    def size: Int        = data.length
    def shortHex: String = hexDigest.take(12)

  final case class IngestResult(
    blocks: List[Block],
    totalBytes: Int,
    uniqueBlocks: Int,
    duplicateBlocks: Int,
    dedupRatio: Double,
    blobDigest: String,
  )

  def apply(): HtmlElement =
    val inputTextVar  = Var("Hello, Graviton! Type or paste anything here to see content-addressed chunking in action.")
    val chunkSizeVar  = Var(32)
    val resultVar     = Var(Option.empty[IngestResult])
    val processingVar = Var(false)
    val modeVar       = Var("text")                 // "text" | "random" | "duplicate"
    val randomSizeVar = Var(256)
    val historyVar    = Var(Map.empty[String, Int]) // digest -> first-seen block index (cross-ingest dedup)

    def processInput(): Unit =
      val bytes = modeVar.now() match
        case "random"    =>
          val arr = new Array[Byte](randomSizeVar.now())
          val rng = new scala.util.Random()
          rng.nextBytes(arr)
          arr
        case "duplicate" =>
          val base = inputTextVar.now().getBytes("UTF-8")
          base ++ base ++ base // Triple the input for guaranteed dedup
        case _           =>
          inputTextVar.now().getBytes("UTF-8")

      if bytes.isEmpty then
        resultVar.set(None)
        return

      processingVar.set(true)
      val blockSize = math.max(8, chunkSizeVar.now())

      // Chunk the data
      val chunks = bytes.grouped(blockSize).toList

      // Hash each chunk (simple hash for browser - deterministic)
      val seenInThisIngest = scala.collection.mutable.Map.empty[String, Int]
      val globalHistory    = historyVar.now()
      val blocks           = chunks.zipWithIndex.map { case (chunk, idx) =>
        val hex   = simpleHash(chunk)
        val isDup = seenInThisIngest.contains(hex) || globalHistory.contains(hex)
        if !seenInThisIngest.contains(hex) then seenInThisIngest(hex) = idx
        Block(idx, chunk, hex, isDup)
      }

      // Update global history
      historyVar.update { h =>
        var updated = h
        blocks.foreach { b =>
          if !updated.contains(b.hexDigest) then updated = updated + (b.hexDigest -> b.index)
        }
        updated
      }

      val blobDigest = simpleHash(bytes)
      val unique     = blocks.count(!_.isDuplicate)
      val dupes      = blocks.count(_.isDuplicate)
      val total      = blocks.length

      resultVar.set(
        Some(
          IngestResult(
            blocks = blocks,
            totalBytes = bytes.length,
            uniqueBlocks = unique,
            duplicateBlocks = dupes,
            dedupRatio = if total > 0 then dupes.toDouble / total.toDouble else 0.0,
            blobDigest = blobDigest,
          )
        )
      )

      processingVar.set(false)

    div(
      cls := "cas-playground",

      // Header
      div(
        cls := "cas-pg-header",
        h2("CAS Playground"),
        p(
          cls := "cas-pg-subtitle",
          "See content-addressed storage in action. Type, paste, or generate data — watch it get chunked, hashed, and deduplicated.",
        ),
      ),

      // Controls row
      div(
        cls := "cas-pg-controls",

        // Mode selector
        div(
          cls := "cas-pg-mode",
          label("Mode: "),
          button(
            cls <-- modeVar.signal.map(m => if m == "text" then "cas-pg-btn active" else "cas-pg-btn"),
            "Text Input",
            onClick --> { _ => modeVar.set("text") },
          ),
          button(
            cls <-- modeVar.signal.map(m => if m == "random" then "cas-pg-btn active" else "cas-pg-btn"),
            "Random Data",
            onClick --> { _ => modeVar.set("random") },
          ),
          button(
            cls <-- modeVar.signal.map(m => if m == "duplicate" then "cas-pg-btn active" else "cas-pg-btn"),
            "Duplicate Test",
            onClick --> { _ => modeVar.set("duplicate") },
          ),
        ),

        // Chunk size slider
        div(
          cls := "cas-pg-chunk-size",
          label(child.text <-- chunkSizeVar.signal.map(s => s"Block size: $s bytes")),
          input(
            typ      := "range",
            minAttr  := "8",
            maxAttr  := "256",
            stepAttr := "8",
            cls      := "cas-pg-slider",
            controlled(
              value <-- chunkSizeVar.signal.map(_.toString),
              onInput.mapToValue.map(_.toInt) --> chunkSizeVar,
            ),
          ),
        ),

        // Random size (visible only in random mode)
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
          div(
            cls := "cas-pg-input",
            p(cls := "cas-pg-random-label", "Random bytes will be generated on each run."),
          )
      },

      // Run button
      div(
        cls := "cas-pg-actions",
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
            historyVar.set(Map.empty)
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
        case None         => div()
        case Some(result) => renderResult(result)
      },
    )

  private def renderResult(result: IngestResult): HtmlElement =
    div(
      cls := "cas-pg-result",

      // Summary stats
      div(
        cls := "cas-pg-stats",
        statCard("Total Bytes", result.totalBytes.toString, "bytes"),
        statCard("Blocks", result.blocks.length.toString, "chunks"),
        statCard("Unique", result.uniqueBlocks.toString, "fresh"),
        statCard("Duplicates", result.duplicateBlocks.toString, "deduped"),
        statCard("Dedup Ratio", f"${result.dedupRatio * 100}%.1f%%", "saved"),
        statCard("Blob Digest", result.blobDigest.take(16) + "...", "sha-sim"),
      ),

      // Block grid visualization
      div(
        cls := "cas-pg-blocks",
        h4("Block Map"),
        p(cls := "cas-pg-blocks-hint", "Each cell is a block. Green = fresh (unique), amber = duplicate. Hover for details."),
        div(
          cls := "cas-pg-block-grid",
          result.blocks.map { block =>
            div(
              cls   := (if block.isDuplicate then "cas-pg-block duplicate" else "cas-pg-block fresh"),
              title := s"Block #${block.index}\nSize: ${block.size} bytes\nDigest: ${block.hexDigest}\n${
                  if block.isDuplicate then "DUPLICATE" else "FRESH"
                }",
              span(cls := "cas-pg-block-idx", block.index.toString),
            )
          },
        ),
      ),

      // Block detail table
      div(
        cls := "cas-pg-detail",
        h4("Block Details"),
        HtmlTag("table")(
          cls := "cas-pg-table",
          HtmlTag("thead")(
            HtmlTag("tr")(
              HtmlTag("th")("#"),
              HtmlTag("th")("Size"),
              HtmlTag("th")("Digest"),
              HtmlTag("th")("Status"),
              HtmlTag("th")("Preview"),
            )
          ),
          HtmlTag("tbody")(
            result.blocks.take(50).map { block =>
              HtmlTag("tr")(
                cls := (if block.isDuplicate then "dup-row" else "fresh-row"),
                HtmlTag("td")(block.index.toString),
                HtmlTag("td")(s"${block.size} B"),
                HtmlTag("td")(code(cls := "cas-pg-digest", block.shortHex)),
                HtmlTag("td")(
                  span(
                    cls := (if block.isDuplicate then "cas-pg-badge dup" else "cas-pg-badge fresh"),
                    if block.isDuplicate then "DUP" else "NEW",
                  )
                ),
                HtmlTag("td")(
                  code(
                    cls := "cas-pg-preview",
                    previewBytes(block.data),
                  )
                ),
              )
            },
            (if result.blocks.length > 50 then
               List(
                 HtmlTag("tr")(
                   HtmlTag("td")(colSpan := 5, s"... and ${result.blocks.length - 50} more blocks")
                 )
               )
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

  private def previewBytes(data: Array[Byte]): String =
    val printable = data.take(24).map { b =>
      if b >= 32 && b < 127 then b.toChar
      else '.'
    }
    new String(printable)

  /** Deterministic hash for browser use (not cryptographic — for visualization only). */
  private def simpleHash(data: Array[Byte]): String =
    var h1 = 0xcafebabe.toLong
    var h2 = 0xdeadbeef.toLong
    var i  = 0
    while i < data.length do
      val b = data(i) & 0xff
      h1 = h1 * 31 + b
      h2 = h2 * 37 + b + (h1 >>> 16)
      h1 ^= (h2 << 7)
      i += 1
    // Mix
    h1 ^= h1 >>> 33
    h1 *= 0xff51afd7ed558ccdL
    h1 ^= h1 >>> 33
    h2 ^= h2 >>> 29
    h2 *= 0xc4ceb9fe1a85ec53L
    h2 ^= h2 >>> 29
    f"$h1%016x$h2%016x"

end CasPlayground
