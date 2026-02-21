package graviton.frontend.components

import com.raquo.laminar.api.L.*
import graviton.shared.ApiModels.*
import graviton.frontend.GravitonApi
import zio.*
import scala.concurrent.ExecutionContext.Implicits.global

/** Interactive blob explorer to view blob metadata and manifests */
object BlobExplorer {

  def apply(api: GravitonApi): HtmlElement = {
    val blobIdVar       = Var("")
    val metadataVar     = Var[Option[BlobMetadata]](None)
    val manifestVar     = Var[Option[BlobManifest]](None)
    val loadingVar      = Var(false)
    val errorVar        = Var[Option[String]](None)
    val showManifestVar = Var(false)

    val runtime = Runtime.default

    def loadSample(blobId: BlobId): Unit = {
      blobIdVar.set(blobId.value)
      loadBlob(blobId.value)
    }

    def loadBlob(blobIdStr: String): Unit = {
      if (blobIdStr.isEmpty) {
        errorVar.set(Some("Please enter a blob ID"))
        return
      }

      loadingVar.set(true)
      errorVar.set(None)
      metadataVar.set(None)
      manifestVar.set(None)
      showManifestVar.set(false)

      val blobId = BlobId.applyUnsafe(blobIdStr)

      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.getBlobMetadata(blobId)).onComplete {
          case scala.util.Success(metadata) =>
            metadataVar.set(Some(metadata))
            loadingVar.set(false)
          case scala.util.Failure(error)    =>
            errorVar.set(Some(error.getMessage))
            loadingVar.set(false)
        }
      }
    }

    def loadManifest(blobId: BlobId): Unit = {
      loadingVar.set(true)

      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.getBlobManifest(blobId)).onComplete {
          case scala.util.Success(manifest) =>
            manifestVar.set(Some(manifest))
            showManifestVar.set(true)
            loadingVar.set(false)
          case scala.util.Failure(error)    =>
            errorVar.set(Some(s"Error loading manifest: ${error.getMessage}"))
            loadingVar.set(false)
        }
      }
    }

    div(
      cls := "blob-explorer",
      h2("🔍 Blob Explorer"),
      div(
        cls := "search-box",
        input(
          cls         := "blob-id-input",
          tpe         := "text",
          placeholder := "Enter blob ID (e.g., sha256:abc123...)",
          controlled(
            value <-- blobIdVar.signal,
            onInput.mapToValue --> blobIdVar.writer,
          ),
          onKeyPress --> { ev =>
            if (ev.key == "Enter") loadBlob(blobIdVar.now())
          },
        ),
        button(
          cls         := "btn-primary",
          "🔍 Load Blob",
          onClick --> { _ => loadBlob(blobIdVar.now()) },
          disabled <-- loadingVar.signal,
        ),
      ),
      child <-- api.offlineSignal.map { offline =>
        if (!offline) emptyNode
        else {
          div(
            cls := "demo-hint",
            p(
              "Running in demo mode. Try one of the sample blob IDs below or start a local Graviton server to explore your own data."
            ),
            div(
              cls := "sample-id-list",
              api.sampleBlobIds.map { blobId =>
                button(
                  cls    := "sample-id-btn",
                  `type` := "button",
                  blobId.value,
                  onClick --> { _ => loadSample(blobId) },
                  disabled <-- loadingVar.signal,
                )
              },
            ),
          )
        }
      },
      child <-- metadataVar.signal.map {
        case None           => emptyNode
        case Some(metadata) =>
          div(
            cls := "blob-details",
            h3("📦 Blob Metadata"),
            div(
              cls := "metadata-grid",
              div(cls := "metadata-row", span(cls := "metadata-label", "ID:"), code(cls := "metadata-value", metadata.id.value)),
              div(
                cls   := "metadata-row",
                span(cls := "metadata-label", "Size:"),
                span(cls := "metadata-value", s"${formatBytes(metadata.size)}"),
              ),
              div(
                cls   := "metadata-row",
                span(cls := "metadata-label", "Content Type:"),
                span(cls := "metadata-value", metadata.contentType.getOrElse("unknown")),
              ),
              div(
                cls   := "metadata-row",
                span(cls := "metadata-label", "Created:"),
                span(cls := "metadata-value", formatTimestamp(metadata.createdAt)),
              ),
              div(
                cls   := "metadata-checksums",
                h4("🔐 Checksums"),
                metadata.checksums.toList.map { case (algo, hash) =>
                  div(
                    cls := "checksum-row",
                    span(cls := "checksum-algo", s"$algo:"),
                    code(cls := "checksum-value", hash),
                  )
                },
              ),
            ),
            button(
              cls := "btn-secondary",
              "📄 View Manifest",
              onClick --> { _ => loadManifest(metadata.id) },
              disabled <-- loadingVar.signal,
            ),
          )
      },
      child <-- manifestVar.signal.combineWith(showManifestVar.signal).map {
        case (Some(manifest), true) =>
          div(
            cls := "manifest-view",
            h3("📄 Blob Manifest"),
            div(cls := "manifest-summary", p(s"Total size: ${formatBytes(manifest.totalSize)}"), p(s"Chunks: ${manifest.chunks.length}")),
            div(
              cls   := "chunks-list",
              h4("🧩 Chunks"),
              div(
                cls := "table-scroll manifest-table-wrapper",
                table(
                  thead(
                    tr(
                      th("Offset"),
                      th("Size"),
                      th("Hash"),
                    )
                  ),
                  tbody(
                    manifest.chunks.map { chunk =>
                      tr(
                        td(formatBytes(chunk.offset)),
                        td(formatBytes(chunk.size)),
                        td(code(chunk.hash.take(16) + "...")),
                      )
                    }
                  ),
                ),
              ),
            ),
          )
        case _                      => emptyNode
      },
      child <-- errorVar.signal.map {
        case None        => emptyNode
        case Some(error) =>
          div(cls := "error-message", s"⚠️ $error")
      },
      child <-- loadingVar.signal.map { loading =>
        if (loading) div(cls := "loading-spinner", "⏳ Loading...")
        else emptyNode
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

  private def formatTimestamp(ts: Long): String = {
    val date = new scala.scalajs.js.Date(ts.toDouble)
    date.toLocaleString()
  }
}
