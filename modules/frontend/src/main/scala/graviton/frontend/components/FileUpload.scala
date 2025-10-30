package graviton.frontend.components

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import org.scalajs.dom.FileReader
import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}
import scala.collection.mutable

/** Interactive file upload component with chunking visualization */
object FileUpload {

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
      val description = "Content-defined chunking (min: 256B, avg: 1KB, max: 4KB)"
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

    def chunkBytes(bytes: js.Array[Byte], chunker: ChunkerType): List[ChunkDisplay] =
      chunker match {
        case ChunkerType.Fixed256 => chunkFixed(bytes, 256)
        case ChunkerType.Fixed1K  => chunkFixed(bytes, 1024)
        case ChunkerType.Fixed4K  => chunkFixed(bytes, 4096)
        case ChunkerType.FastCDC  => chunkFastCDC(bytes, min = 256, avg = 1024, max = 4096)
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
     * FastCDC implementation using the same algorithm as graviton-core.
     *
     * This implements content-defined chunking with a rolling hash,
     * identical to the server-side implementation in graviton-streams.
     * Min/avg/max boundaries ensure predictable chunk sizes while
     * maintaining content-defined splitting for deduplication.
     */
    def chunkFastCDC(bytes: js.Array[Byte], min: Int, avg: Int, max: Int): List[ChunkDisplay] = {
      val chunks = mutable.ListBuffer[ChunkDisplay]()
      val mask   = (1 << (log2(avg) - 1)) - 1

      var offset     = 0L
      var i          = 0
      var chunkStart = 0
      var roll       = 0L

      while (i < bytes.length) {
        val b = bytes(i) & 0xff
        roll = ((roll << 1) ^ b) & 0xffffff

        val currentSize = i - chunkStart + 1
        val isBoundary  =
          (currentSize >= min && (roll & mask) == 0) || // Rolling hash match
            currentSize >= max ||                       // Max size reached
            i == bytes.length - 1                       // End of file

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
          roll = 0L
        }

        i += 1
      }

      chunks.toList
    }

    def log2(n: Int): Int =
      if (n <= 0) 0
      else 32 - Integer.numberOfLeadingZeros(n - 1)

    def processFile(file: dom.File, chunker: ChunkerType): Unit = {
      val reader = new FileReader()

      reader.onload = (e: dom.Event) => {
        val arrayBuffer = reader.result.asInstanceOf[ArrayBuffer]
        val uint8Array  = new Uint8Array(arrayBuffer)
        val bytes       = new js.Array[Byte](uint8Array.length)

        var i = 0
        while (i < uint8Array.length) {
          bytes(i) = uint8Array(i).toByte
          i += 1
        }

        val validations = validateFile(file)
        val chunks      = chunkBytes(bytes, chunker)

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
        processingVar.set(false)
      }

      reader.onerror = (e: dom.Event) => {
        errorVar.set(Some(s"Error reading file: ${file.name}"))
        processingVar.set(false)
      }

      reader.readAsArrayBuffer(file)
    }

    def handleFiles(files: List[dom.File]): Unit = {
      if (files.isEmpty) return

      errorVar.set(None)
      processingVar.set(true)
      filesVar.update(_ ++ files)

      files.foreach { file =>
        processFile(file, selectedChunkerVar.now())
      }
    }

    def clearAll(): Unit = {
      filesVar.set(List.empty)
      analysesVar.set(Map.empty)
      globalChunkMapVar.set(Map.empty)
      errorVar.set(None)
    }

    div(
      cls := "file-upload",
      h2("📤 File Upload & Chunking Demo"),
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
        h3("🔧 Chunker Strategy"),
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
                if (filesVar.now().nonEmpty) {
                  analysesVar.set(Map.empty)
                  globalChunkMapVar.set(Map.empty)
                  processingVar.set(true)
                  filesVar.now().foreach(file => processFile(file, chunker))
                }
              },
              div(cls := "chunker-name", chunker.name),
              div(cls := "chunker-desc", chunker.description),
            )
          },
        ),
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
          "🗑️ Clear All",
          onClick --> { _ => clearAll() },
          disabled <-- analysesVar.signal.map(_.isEmpty),
        ),
      ),

      // Error display
      child <-- errorVar.signal.map {
        case None        => emptyNode
        case Some(error) =>
          div(cls := "error-message", s"⚠️ $error")
      },

      // Processing indicator
      child <-- processingVar.signal.map { processing =>
        if (processing) div(cls := "loading-spinner", "⏳ Processing files...")
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

          div(
            cls := "global-stats",
            h3("📊 Global Deduplication Statistics"),
            div(
              cls := "stats-grid-compact",
              div(cls := "stat-item", span(cls := "stat-label", "Total Files:"), span(cls := "stat-value", analyses.size.toString)),
              div(cls := "stat-item", span(cls := "stat-label", "Total Chunks:"), span(cls := "stat-value", totalChunks.toString)),
              div(cls := "stat-item", span(cls := "stat-label", "Unique Chunks:"), span(cls := "stat-value", uniqueChunks.toString)),
              div(cls := "stat-item", span(cls := "stat-label", "Shared Chunks:"), span(cls := "stat-value", sharedChunks.toString)),
              div(cls := "stat-item", span(cls := "stat-label", "Dedup Ratio:"), span(cls := "stat-value", f"$deduplicationRatio%.3f")),
              div(cls := "stat-item", span(cls := "stat-label", "Space Savings:"), span(cls := "stat-value", f"$spaceSavings%.1f%%")),
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

  private def renderFileAnalysis(analysis: FileAnalysis): HtmlElement = {
    val expandedVar = Var(false)

    div(
      cls := "file-analysis",
      div(
        cls := "file-header",
        onClick --> { _ => expandedVar.update(!_) },
        div(
          cls := "file-info",
          h4(cls := "file-name", s"📄 ${analysis.fileName}"),
          div(
            cls  := "file-meta",
            span(s"${formatBytes(analysis.fileSize)} • "),
            span(s"${analysis.totalChunks} chunks • "),
            span(s"${analysis.chunkerType}"),
          ),
        ),
        span(
          cls := "expand-icon",
          child <-- expandedVar.signal.map { expanded =>
            if (expanded) span("▼") else span("▶")
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
              h5("✅ Validations"),
              div(
                cls := "validations-list",
                analysis.validations.map { validation =>
                  div(
                    cls := s"validation-item ${if (validation.isError) "error" else "success"}",
                    span(cls := "validation-icon", if (validation.isError) "❌" else "✅"),
                    span(validation.message),
                  )
                },
              ),
            ),

            // Chunk stats
            div(
              cls := "chunk-stats",
              h5("🧩 Chunk Statistics"),
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
              ),
            ),

            // Chunks table
            div(
              cls := "chunks-section",
              h5(s"📦 Chunks (${analysis.chunks.length})"),
              div(
                cls := "chunks-table-wrapper",
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
                        cls                        := "chunk-row",
                        cls.toggle("shared-chunk") := chunk.sharedWith.nonEmpty,
                        td(formatBytes(chunk.offset)),
                        td(formatBytes(chunk.size)),
                        td(code(cls := "hash-value", chunk.hash)),
                        td(
                          if (chunk.sharedWith.isEmpty) span(cls := "no-sharing", "—")
                          else
                            div(
                              cls                                := "shared-files",
                              span(cls := "share-indicator", s"🔗 ${chunk.sharedWith.size}"),
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
