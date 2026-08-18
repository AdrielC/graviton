package graviton.frontend.components

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import org.scalajs.dom.FileReader
import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}
import scala.collection.mutable

/** Interactive file upload component with chunking visualization */
object FileUpload {

  final case class FastCDCConfig(min: Int, avg: Int, max: Int)

  object FastCDCConfig {
    val default: FastCDCConfig = FastCDCConfig(256, 1024, 4096)

    def normalize(config: FastCDCConfig): FastCDCConfig = {
      val minBound = config.min.max(64).min(1 << 18)
      val avgBound = config.avg.max(minBound).min(1 << 19)
      val maxBound = config.max.max(avgBound).min(1 << 20)
      FastCDCConfig(minBound, avgBound, maxBound)
    }
  }

  // Chunk information for display
  final case class ChunkDisplay(
    offset: Long,
    size: Int,
    hash: String,
    sharedWith: List[String] = List.empty, // Other files sharing this chunk
  )

  // File analysis result
  final case class FileAnalysis(
    fileName: String,
    fileSize: Long,
    chunks: List[ChunkDisplay],
    totalChunks: Int,
    uniqueChunks: Int,
    deduplicationRatio: Double,
    chunkerType: String,
    validations: List[ValidationResult],
    averageChunkSize: Double,
    minChunkSize: Int,
    maxChunkSize: Int,
    fastcdcConfig: Option[FastCDCConfig],
  )

  // Validation results
  sealed trait ValidationResult {
    def message: String
    def isError: Boolean
  }

  object ValidationResult {
    final case class Success(message: String) extends ValidationResult {
      def isError: Boolean = false
    }
    final case class Warning(message: String) extends ValidationResult {
      def isError: Boolean = false
    }
    final case class Error(message: String)   extends ValidationResult {
      def isError: Boolean = true
    }
  }

  // Chunker strategies
  sealed trait ChunkerType {
    def name: String
    def description: String
  }

  object ChunkerType {
    case object Fixed256 extends ChunkerType {
      val name        = "Fixed 256B"
      val description = "Fixed-size chunks of 256 bytes"
    }
    case object Fixed1K  extends ChunkerType {
      val name        = "Fixed 1KB"
      val description = "Fixed-size chunks of 1024 bytes"
    }
    case object Fixed4K  extends ChunkerType {
      val name        = "Fixed 4KB"
      val description = "Fixed-size chunks of 4096 bytes"
    }
    case object FastCDC  extends ChunkerType {
      val name        = "FastCDC"
      val description = "Content-defined chunking (configure min/avg/max below)"
    }

    val all: List[ChunkerType] = List(Fixed256, Fixed1K, Fixed4K, FastCDC)
  }

  def apply(): HtmlElement = {
    val filesVar           = Var[List[dom.File]](List.empty)
    val analysesVar        = Var[Map[String, FileAnalysis]](Map.empty)
    val selectedChunkerVar = Var[ChunkerType](ChunkerType.Fixed1K)
    val processingVar      = Var(false)
    val errorVar           = Var[Option[String]](None)
    val globalChunkMapVar  = Var[Map[String, List[String]]](Map.empty) // hash -> list of file names
    val fastCDCConfigVar   = Var(FastCDCConfig.default)
    val pendingReadsVar    = Var(0)

    def validateFile(file: dom.File): List[ValidationResult] = {
      val validations = mutable.ListBuffer[ValidationResult]()

      // Check file size
      if (file.size == 0) {
        validations += ValidationResult.Error("File is empty")
      } else {
        validations += ValidationResult.Success(s"File size: ${formatBytes(file.size.toLong)}")
      }

      // Check file size limits
      if (file.size > 10 * 1024 * 1024) {
        validations += ValidationResult.Warning("File is larger than 10MB - processing may be slow")
      }

      // Check file name
      if (file.name.isEmpty) {
        validations += ValidationResult.Warning("File has no name")
      } else {
        validations += ValidationResult.Success(s"File name: ${file.name}")
      }

      // Detect content type
      if (file.`type`.nonEmpty) {
        validations += ValidationResult.Success(s"Content type: ${file.`type`}")
      } else {
        validations += ValidationResult.Warning("No content type detected")
      }

      validations.toList
    }

    def addPending(count: Int): Unit =
      if count > 0 then
        pendingReadsVar.update(_ + count)
        processingVar.set(true)

    def resolvePending(): Unit =
      val remaining = (pendingReadsVar.now() - 1).max(0)
      pendingReadsVar.set(remaining)
      if remaining == 0 then processingVar.set(false)

    def reprocessAllFiles(chunker: ChunkerType): Unit =
      val existing = filesVar.now()
      if existing.nonEmpty then
        analysesVar.set(Map.empty)
        globalChunkMapVar.set(Map.empty)
        addPending(existing.size)
        existing.foreach(file => processFile(file, chunker))

    def updateFastCDCConfig(modifier: FastCDCConfig => FastCDCConfig): Unit =
      val normalized = FastCDCConfig.normalize(modifier(fastCDCConfigVar.now()))
      if normalized != fastCDCConfigVar.now() then
        fastCDCConfigVar.set(normalized)
        if selectedChunkerVar.now() == ChunkerType.FastCDC && filesVar.now().nonEmpty then reprocessAllFiles(ChunkerType.FastCDC)

    def renderFastCDCControls(): HtmlElement = {
      def control(
        labelText: String,
        valueOf: FastCDCConfig => Int,
        updateField: (FastCDCConfig, Int) => FastCDCConfig,
        minValue: Int,
        maxValue: Int,
        stepValue: Int,
      ): HtmlElement = {
        val observer = Observer[String] { rawValue =>
          rawValue.toIntOption.foreach { value =>
            updateFastCDCConfig(cfg => updateField(cfg, value))
          }
        }

        label(
          cls := "config-field",
          span(cls   := "config-label", labelText),
          input(
            tpe      := "range",
            minAttr  := minValue.toString,
            maxAttr  := maxValue.toString,
            stepAttr := stepValue.toString,
            controlled(
              value <-- fastCDCConfigVar.signal.map(cfg => valueOf(cfg).toString),
              onInput.mapToValue --> observer,
            ),
          ),
          input(
            tpe      := "number",
            minAttr  := minValue.toString,
            maxAttr  := maxValue.toString,
            stepAttr := stepValue.toString,
            controlled(
              value <-- fastCDCConfigVar.signal.map(cfg => valueOf(cfg).toString),
              onInput.mapToValue --> observer,
            ),
          ),
          span(
            cls      := "config-value",
            child.text <-- fastCDCConfigVar.signal.map(cfg => formatBytes(valueOf(cfg).toLong)),
          ),
        )
      }

      div(
        cls := "fastcdc-config",
        h4("CAS Chunk Tuner"),
        p(
          cls := "config-help",
          "Adjust FastCDC boundaries to explore how chunk sizes change. Smaller windows create more dedup-friendly blocks; larger windows reduce manifest overhead.",
        ),
        div(
          cls := "config-grid",
          control("Minimum chunk", _.min, (cfg, value) => cfg.copy(min = value), 64, 65536, 64),
          control("Target average", _.avg, (cfg, value) => cfg.copy(avg = value), 128, 262144, 64),
          control("Maximum chunk", _.max, (cfg, value) => cfg.copy(max = value), 1024, 1048576, 256),
        ),
        div(
          cls := "config-summary",
          child.text <-- fastCDCConfigVar.signal.map { cfg =>
            val normalized = FastCDCConfig.normalize(cfg)
            s"Effective bounds => min ${formatBytes(normalized.min.toLong)}, avg ${formatBytes(normalized.avg.toLong)}, max ${formatBytes(normalized.max.toLong)}"
          },
        ),
      )
    }

    def simpleHash(bytes: js.Array[Byte]): String = {
      // Simple hash for demo purposes (not cryptographic)
      var hash    = 5381L
      var i       = 0
      while (i < bytes.length) {
        hash = ((hash << 5) + hash) + (bytes(i) & 0xff)
        i += 1
      }
      val hashStr = (hash & 0xffffffffL).toHexString
      "0x" + "0" * (8 - hashStr.length) + hashStr
    }

    def chunkBytes(bytes: js.Array[Byte], chunker: ChunkerType, fastConfig: FastCDCConfig): List[ChunkDisplay] =
      chunker match {
        case ChunkerType.Fixed256 => chunkFixed(bytes, 256)
        case ChunkerType.Fixed1K  => chunkFixed(bytes, 1024)
        case ChunkerType.Fixed4K  => chunkFixed(bytes, 4096)
        case ChunkerType.FastCDC  => chunkFastCDC(bytes, fastConfig)
      }

    def chunkFixed(bytes: js.Array[Byte], size: Int): List[ChunkDisplay] = {
      val chunks = mutable.ListBuffer[ChunkDisplay]()
      var offset = 0L
      var i      = 0

      while (i < bytes.length) {
        val chunkSize  = math.min(size, bytes.length - i)
        val chunkBytes = bytes.slice(i, i + chunkSize)
        val hash       = simpleHash(chunkBytes)

        chunks += ChunkDisplay(
          offset = offset,
          size = chunkSize,
          hash = hash,
        )

        offset += chunkSize
        i += chunkSize
      }

      chunks.toList
    }

    /**
     * FastCDC implementation using the same algorithm as `graviton.streams.Chunker.fastCdc`.
     *
     * This implements content-defined chunking with a rolling hash,
     * identical to the server-side implementation in graviton-streams.
     * Min/avg/max boundaries ensure predictable chunk sizes while
     * maintaining content-defined splitting for deduplication.
     */
    def chunkFastCDC(bytes: js.Array[Byte], config: FastCDCConfig): List[ChunkDisplay] = {
      val chunks                   = mutable.ListBuffer[ChunkDisplay]()
      val normalized               = FastCDCConfig.normalize(config)
      val minSize                  = normalized.min
      val avgSize                  = normalized.avg
      val maxSize                  = normalized.max
      val (strongMask, normalMask) = fastCdcMasks(avgSize)

      var offset     = 0L
      var i          = 0
      var chunkStart = 0
      var h          = 0

      while (i < bytes.length) {
        val b           = bytes(i) & 0xff
        val currentSize = i - chunkStart + 1
        h = (h << 1) + gear(b)

        val wantsBoundary =
          if (currentSize < minSize) false
          else if (currentSize < avgSize) (h & strongMask) == 0
          else (h & normalMask) == 0

        val isBoundary = wantsBoundary || currentSize >= maxSize || i == bytes.length - 1

        if (isBoundary) {
          val chunkBytes = bytes.slice(chunkStart, i + 1)
          val hash       = simpleHash(chunkBytes)

          chunks += ChunkDisplay(
            offset = offset,
            size = chunkBytes.length,
            hash = hash,
          )

          offset += chunkBytes.length
          chunkStart = i + 1
          h = 0
        }

        i += 1
      }

      chunks.toList
    }

    def log2(n: Int): Int =
      if (n <= 0) 0
      else 32 - Integer.numberOfLeadingZeros(n - 1)

    lazy val gear: Array[Int] = {
      val g = Array.ofDim[Int](256)
      var i = 0
      var x = 0x9e3779b9
      while (i < 256) {
        x = Integer.rotateLeft(x ^ (i * 0x85ebca6b), 13) * 0xc2b2ae35
        g(i) = x
        i += 1
      }
      g
    }

    def fastCdcMasks(avgBytes: Int): (Int, Int) = {
      val avg        = math.max(avgBytes, 1)
      val bits       = 31 - Integer.numberOfLeadingZeros(avg)
      val normalBits = math.max(8, math.min(20, bits))
      val strongBits = math.max(6, math.min(18, bits - 1))
      val normalMask = (1 << normalBits) - 1
      val strongMask = (1 << strongBits) - 1
      (strongMask, normalMask)
    }

    def processFile(file: dom.File, chunker: ChunkerType): Unit = {
      val reader = new FileReader()

      reader.onload = (_: dom.Event) => {
        val arrayBuffer = reader.result.asInstanceOf[ArrayBuffer]
        val uint8Array  = new Uint8Array(arrayBuffer)
        val bytes       = new js.Array[Byte](uint8Array.length)

        var i = 0
        while (i < uint8Array.length) {
          bytes(i) = uint8Array(i).toByte
          i += 1
        }

        val validations = validateFile(file)
        val fastConfig  = fastCDCConfigVar.now()
        val chunks      = chunkBytes(bytes, chunker, fastConfig)

        val sizeList          = chunks.map(_.size)
        val averageChunkSize  = if (sizeList.nonEmpty) sizeList.sum.toDouble / sizeList.length else 0.0
        val minChunkSize      = if (sizeList.nonEmpty) sizeList.min else 0
        val maxChunkSize      = if (sizeList.nonEmpty) sizeList.max else 0
        val configForAnalysis = if (chunker == ChunkerType.FastCDC) Some(fastConfig) else None

        // Update global chunk map for deduplication analysis
        val currentMap = globalChunkMapVar.now()
        val updatedMap = chunks.foldLeft(currentMap) { (map, chunk) =>
          val files = map.getOrElse(chunk.hash, List.empty)
          map.updated(chunk.hash, (file.name :: files).distinct)
        }
        globalChunkMapVar.set(updatedMap)

        // Add shared file info to chunks
        val chunksWithSharing = chunks.map { chunk =>
          val sharedFiles = updatedMap.getOrElse(chunk.hash, List.empty).filterNot(_ == file.name)
          chunk.copy(sharedWith = sharedFiles)
        }

        // Calculate deduplication metrics
        val allChunks          = analysesVar.now().values.flatMap(_.chunks).toList ++ chunksWithSharing
        val uniqueHashes       = allChunks.map(_.hash).distinct.size
        val totalChunks        = allChunks.size
        val deduplicationRatio = if (totalChunks > 0) uniqueHashes.toDouble / totalChunks else 1.0

        val analysis = FileAnalysis(
          fileName = file.name,
          fileSize = file.size.toLong,
          chunks = chunksWithSharing,
          totalChunks = chunks.length,
          uniqueChunks = chunks.map(_.hash).distinct.size,
          deduplicationRatio = deduplicationRatio,
          chunkerType = chunker.name,
          validations = validations,
          averageChunkSize = averageChunkSize,
          minChunkSize = minChunkSize,
          maxChunkSize = maxChunkSize,
          fastcdcConfig = configForAnalysis,
        )

        // Update all analyses to reflect new sharing information
        val currentAnalyses = analysesVar.now()
        val updatedAnalyses = currentAnalyses.map { case (name, oldAnalysis) =>
          val updatedChunks = oldAnalysis.chunks.map { chunk =>
            val sharedFiles = updatedMap.getOrElse(chunk.hash, List.empty).filterNot(_ == name)
            chunk.copy(sharedWith = sharedFiles)
          }
          name -> oldAnalysis.copy(
            chunks = updatedChunks,
            deduplicationRatio = deduplicationRatio,
          )
        }

        analysesVar.set(updatedAnalyses + (file.name -> analysis))
        resolvePending()
      }

      reader.onerror = (_: dom.Event) => {
        errorVar.set(Some(s"Error reading file: ${file.name}"))
        resolvePending()
      }

      reader.readAsArrayBuffer(file)
    }

    def handleFiles(files: List[dom.File]): Unit = {
      if (files.isEmpty) return

      errorVar.set(None)
      filesVar.update(_ ++ files)
      addPending(files.length)
      files.foreach { file =>
        processFile(file, selectedChunkerVar.now())
      }
    }

    def clearAll(): Unit = {
      filesVar.set(List.empty)
      analysesVar.set(Map.empty)
      globalChunkMapVar.set(Map.empty)
      errorVar.set(None)
      pendingReadsVar.set(0)
      processingVar.set(false)
    }

    div(
      cls := "file-upload",
      h2("File Upload & Chunking Lab"),
      p(
        cls := "upload-intro",
        """
        Upload files to see how Graviton chunks them for content-addressable storage.
        Upload multiple files to see block sharing and deduplication in action!
        """,
      ),

      // Chunker selection
      div(
        cls := "chunker-selection",
        h3("Chunker Strategy"),
        div(
          cls := "chunker-buttons",
          ChunkerType.all.map { chunker =>
            button(
              cls := "chunker-btn",
              cls <-- selectedChunkerVar.signal.map { selected =>
                if (selected == chunker) "active" else ""
              },
              onClick --> { _ =>
                selectedChunkerVar.set(chunker)
                // Re-process all files with new chunker
                if (filesVar.now().nonEmpty) then reprocessAllFiles(chunker)
              },
              div(cls := "chunker-name", chunker.name),
              div(cls := "chunker-desc", chunker.description),
            )
          },
        ),
        child <-- selectedChunkerVar.signal.map {
          case ChunkerType.FastCDC => renderFastCDCControls()
          case _                   => emptyNode
        },
      ),

      // File input
      div(
        cls := "upload-area",
        input(
          tpe      := "file",
          multiple := true,
          cls      := "file-input",
          onChange --> { ev =>
            val files = ev.target.asInstanceOf[dom.HTMLInputElement].files
            if (files != null) {
              val fileList = (0 until files.length).map(i => files.item(i)).toList
              handleFiles(fileList)
            }
          },
        ),
        button(
          cls      := "btn-secondary clear-btn",
          "Clear All",
          onClick --> { _ => clearAll() },
          disabled <-- analysesVar.signal.map(_.isEmpty),
        ),
      ),

      // Error display
      child <-- errorVar.signal.map {
        case None        => emptyNode
        case Some(error) =>
          div(cls := "error-message", s"Error: $error")
      },

      // Processing indicator
      child <-- processingVar.signal.map { processing =>
        if (processing) div(cls := "loading-spinner", "Processing files...")
        else emptyNode
      },

      // Global deduplication stats
      child <-- analysesVar.signal.combineWith(globalChunkMapVar.signal).map { (analyses, chunkMap) =>
        if (analyses.isEmpty) emptyNode
        else {
          val allChunks          = analyses.values.flatMap(_.chunks).toList
          val totalChunks        = allChunks.size
          val uniqueChunks       = allChunks.map(_.hash).distinct.size
          val sharedChunks       = chunkMap.count(_._2.size > 1)
          val deduplicationRatio = if (totalChunks > 0) uniqueChunks.toDouble / totalChunks else 1.0
          val spaceSavings       = if (totalChunks > 0) (1.0 - deduplicationRatio) * 100 else 0.0
          val avgChunkSize       = if (totalChunks > 0) allChunks.map(_.size).sum.toDouble / totalChunks else 0.0
          val maxChunkSize       = if (allChunks.nonEmpty) allChunks.map(_.size).max else 0

          div(
            cls := "global-stats",
            h3("Deduplication Overview"),
            div(
              cls := "stats-grid-compact",
              div(cls := "stat-item", span(cls := "stat-label", "Total Files:"), span(cls := "stat-value", analyses.size.toString)),
              div(cls := "stat-item", span(cls := "stat-label", "Total Chunks:"), span(cls := "stat-value", totalChunks.toString)),
              div(cls := "stat-item", span(cls := "stat-label", "Unique Chunks:"), span(cls := "stat-value", uniqueChunks.toString)),
              div(cls := "stat-item", span(cls := "stat-label", "Shared Chunks:"), span(cls := "stat-value", sharedChunks.toString)),
              div(cls := "stat-item", span(cls := "stat-label", "Dedup Ratio:"), span(cls := "stat-value", f"$deduplicationRatio%.3f")),
              div(cls := "stat-item", span(cls := "stat-label", "Space Savings:"), span(cls := "stat-value", f"$spaceSavings%.1f%%")),
              div(
                cls   := "stat-item",
                span(cls := "stat-label", "Avg Chunk Size:"),
                span(cls := "stat-value", formatBytes(math.round(avgChunkSize).toLong)),
              ),
              div(
                cls   := "stat-item",
                span(cls := "stat-label", "Largest Chunk:"),
                span(cls := "stat-value", formatBytes(maxChunkSize.toLong)),
              ),
            ),
          )
        }
      },

      // File analyses
      child <-- analysesVar.signal.map { analyses =>
        if (analyses.isEmpty) emptyNode
        else {
          div(
            cls := "analyses-list",
            analyses.values.toList.sortBy(_.fileName).map { analysis =>
              renderFileAnalysis(analysis)
            },
          )
        }
      },
    )
  }

  private def renderChunkVisualization(analysis: FileAnalysis): HtmlElement = {
    val totalSize = analysis.chunks.map(_.size.toLong).sum
    if (totalSize <= 0) then div(cls := "chunk-visualization-empty", "No chunk data available yet")
    else {
      val bars = analysis.chunks.map { chunk =>
        val widthPct         = math.max(0.5, chunk.size.toDouble / totalSize * 100)
        val barClasses       = if (chunk.sharedWith.nonEmpty) "chunk-bar shared" else "chunk-bar"
        div(
          cls       := barClasses,
          styleAttr := s"flex-basis:$widthPct%;",
          title     := s"${formatBytes(chunk.size.toLong)} starting at ${formatBytes(chunk.offset)}",
        )
      }

      div(
        cls := "chunk-visualization",
        div(cls := "chunk-bars", bars),
        div(
          cls   := "chunk-legend",
          span(cls := "legend-item", span(cls := "legend-swatch unique"), "Unique chunk"),
          span(cls := "legend-item", span(cls := "legend-swatch shared"), "Shared chunk"),
        ),
      )
    }
  }

  private def renderFileAnalysis(analysis: FileAnalysis): HtmlElement = {
    val expandedVar = Var(false)

    div(
      cls := "file-analysis",
      div(
        cls := "file-header",
        onClick --> { _ => expandedVar.update(!_) },
        div(
          cls := "file-info",
          h4(cls := "file-name", analysis.fileName),
          div(
            cls  := "file-meta",
            span(formatBytes(analysis.fileSize)),
            span(cls := "file-meta-divider", "|"),
            span(s"${analysis.totalChunks} chunks"),
            span(cls := "file-meta-divider", "|"),
            span(analysis.chunkerType),
          ),
        ),
        span(
          cls := "expand-icon",
          child <-- expandedVar.signal.map { expanded =>
            if (expanded) span("-") else span("+")
          },
        ),
      ),
      child <-- expandedVar.signal.map { expanded =>
        if (!expanded) emptyNode
        else {
          div(
            cls := "file-details-expanded",

            // Validations
            div(
              cls := "validations-section",
              h5("Validation Checklist"),
              div(
                cls := "validations-list",
                analysis.validations.map { validation =>
                  val (severityClass, glyph) = validation match
                    case _: ValidationResult.Error   => ("error", "X")
                    case _: ValidationResult.Warning => ("warning", "!")
                    case _: ValidationResult.Success => ("success", "OK")

                  div(
                    cls := s"validation-item $severityClass",
                    span(cls := "validation-icon", glyph),
                    span(validation.message),
                  )
                },
              ),
            ),

            // Chunk stats
            div(
              cls := "chunk-stats",
              h5("Chunk Statistics"),
              div(
                cls := "stats-grid-compact",
                div(
                  cls := "stat-item",
                  span(cls := "stat-label", "Total Chunks:"),
                  span(cls := "stat-value", analysis.totalChunks.toString),
                ),
                div(
                  cls := "stat-item",
                  span(cls := "stat-label", "Unique Chunks:"),
                  span(cls := "stat-value", analysis.uniqueChunks.toString),
                ),
                div(
                  cls := "stat-item",
                  span(cls := "stat-label", "Shared Chunks:"),
                  span(cls := "stat-value", (analysis.totalChunks - analysis.chunks.count(_.sharedWith.isEmpty)).toString),
                ),
                div(
                  cls := "stat-item",
                  span(cls := "stat-label", "Average Size:"),
                  span(cls := "stat-value", formatBytes(math.round(analysis.averageChunkSize).toLong)),
                ),
                div(
                  cls := "stat-item",
                  span(cls := "stat-label", "Smallest Chunk:"),
                  span(cls := "stat-value", formatBytes(analysis.minChunkSize.toLong)),
                ),
                div(
                  cls := "stat-item",
                  span(cls := "stat-label", "Largest Chunk:"),
                  span(cls := "stat-value", formatBytes(analysis.maxChunkSize.toLong)),
                ),
              ),
              analysis.fastcdcConfig match
                case Some(config) =>
                  div(
                    cls := "fastcdc-used",
                    span(cls := "stat-label", "FastCDC Config:"),
                    span(
                      cls    := "stat-value",
                      s"min ${formatBytes(config.min.toLong)} | avg ${formatBytes(config.avg.toLong)} | max ${formatBytes(config.max.toLong)}",
                    ),
                  )
                case None         => emptyNode,
            ),

            // Visualization of chunk boundaries
            div(
              cls := "chunk-visualizer-section",
              h5("Chunk Breakpoints"),
              renderChunkVisualization(analysis),
            ),

            // Chunks table
            div(
              cls := "chunks-section",
              h5(s"Chunk Table (${analysis.chunks.length})"),
              div(
                cls := "chunks-table-wrapper table-scroll",
                table(
                  cls := "chunks-table",
                  thead(
                    tr(
                      th("Offset"),
                      th("Size"),
                      th("Hash"),
                      th("Shared With"),
                    )
                  ),
                  tbody(
                    analysis.chunks.take(100).map { chunk =>
                      tr(
                        cls := (if (chunk.sharedWith.nonEmpty) "chunk-row shared-chunk" else "chunk-row"),
                        td(formatBytes(chunk.offset)),
                        td(formatBytes(chunk.size)),
                        td(code(cls := "hash-value", chunk.hash)),
                        td(
                          if (chunk.sharedWith.isEmpty) span(cls := "no-sharing", "No shared files yet")
                          else
                            div(
                              cls                                := "shared-files",
                              span(cls := "share-indicator", s"${chunk.sharedWith.size} shared"),
                              span(cls := "share-tooltip", chunk.sharedWith.mkString(", ")),
                            )
                        ),
                      )
                    }
                  ),
                ),
                if (analysis.chunks.length > 100) {
                  div(cls := "truncation-notice", s"Showing first 100 of ${analysis.chunks.length} chunks")
                } else emptyNode,
              ),
            ),
          )
        }
      },
    )
  }

  private def formatBytes(bytes: Long): String = {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0

    if (gb >= 1) f"$gb%.2f GB"
    else if (mb >= 1) f"$mb%.2f MB"
    else if (kb >= 1) f"$kb%.2f KB"
    else s"$bytes B"
  }
}
