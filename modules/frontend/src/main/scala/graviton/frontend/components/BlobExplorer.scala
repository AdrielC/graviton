package graviton.frontend.components

import com.raquo.laminar.api.L.*
import graviton.frontend.GravitonApi
import graviton.shared.ApiModels.*
import org.scalajs.dom
import zio.*

import scala.concurrent.ExecutionContext.Implicits.global

/** Durable inventory and manifest inspector backed entirely by the server API. */
object BlobExplorer:

  def apply(api: GravitonApi): HtmlElement =
    val inventoryVar    = Var[List[BlobSummary]](Nil)
    val detailsVar      = Var[Option[BlobDetails]](None)
    val verificationVar = Var[Option[BlobVerificationResult]](None)
    val blobIdVar       = Var("")
    val loadingVar      = Var(false)
    val errorVar        = Var[Option[String]](None)
    val runtime         = Runtime.default

    def refreshInventory(): Unit =
      loadingVar.set(true)
      errorVar.set(None)
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.listBlobs).onComplete {
          case scala.util.Success(response) =>
            inventoryVar.set(response.blobs)
            loadingVar.set(false)
          case scala.util.Failure(error)    =>
            inventoryVar.set(Nil)
            errorVar.set(Some(error.getMessage))
            loadingVar.set(false)
        }
      }

    def inspect(blobId: String): Unit =
      val trimmed = blobId.trim
      if trimmed.isEmpty then errorVar.set(Some("Enter a complete content ID."))
      else
        loadingVar.set(true)
        detailsVar.set(None)
        verificationVar.set(None)
        errorVar.set(None)
        Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.runToFuture(api.inspectBlob(trimmed)).onComplete {
            case scala.util.Success(details) =>
              blobIdVar.set(details.summary.id.value)
              detailsVar.set(Some(details))
              loadingVar.set(false)
            case scala.util.Failure(error)   =>
              errorVar.set(Some(error.getMessage))
              loadingVar.set(false)
          }
        }

    def verify(blobId: String): Unit =
      loadingVar.set(true)
      verificationVar.set(None)
      errorVar.set(None)
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.verifyBlob(blobId)).onComplete {
          case scala.util.Success(result) =>
            verificationVar.set(Some(result))
            loadingVar.set(false)
          case scala.util.Failure(error)  =>
            errorVar.set(Some(error.getMessage))
            loadingVar.set(false)
        }
      }

    def download(blobId: String): Unit =
      loadingVar.set(true)
      errorVar.set(None)
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.downloadBlob(blobId)).onComplete {
          case scala.util.Success(_)     => loadingVar.set(false)
          case scala.util.Failure(error) =>
            errorVar.set(Some(error.getMessage))
            loadingVar.set(false)
        }
      }

    def delete(blobId: String): Unit =
      if dom.window.confirm("Delete this blob manifest? Shared content-addressed blocks are retained.") then
        loadingVar.set(true)
        errorVar.set(None)
        Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.runToFuture(api.deleteBlob(blobId)).onComplete {
            case scala.util.Success(_)     =>
              detailsVar.set(None)
              verificationVar.set(None)
              blobIdVar.set("")
              refreshInventory()
            case scala.util.Failure(error) =>
              errorVar.set(Some(error.getMessage))
              loadingVar.set(false)
          }
        }

    div(
      cls := "blob-explorer",
      onMountCallback(_ => refreshInventory()),
      h2("Persisted blob inventory"),
      p(
        cls   := "page-intro",
        "This inventory is read from the configured manifest repository. Select a row to inspect the exact persisted block layout.",
      ),
      div(cls := "connection-target", span("Server"), code(api.baseUrl)),
      div(
        cls   := "search-box",
        input(
          cls         := "blob-id-input",
          tpe         := "text",
          placeholder := "sha-256:<digest>:<bytes>",
          controlled(value <-- blobIdVar.signal, onInput.mapToValue --> blobIdVar.writer),
          onKeyPress --> { event => if event.key == "Enter" then inspect(blobIdVar.now()) },
        ),
        button(cls    := "btn-primary", "Inspect", onClick --> { _ => inspect(blobIdVar.now()) }),
        button(cls    := "btn-secondary", "Refresh inventory", onClick --> { _ => refreshInventory() }),
      ),
      child <-- inventoryVar.signal.combineWith(loadingVar.signal).map { case (blobs, loading) =>
        if loading && blobs.isEmpty then div(cls := "loading-spinner", "Loading persisted manifests...")
        else if blobs.isEmpty then div(cls := "stats-empty", "The connected store has no blob manifests.")
        else
          div(
            cls                            := "table-scroll inventory-table-wrapper",
            table(
              thead(tr(th("Content ID"), th("Size"), th("Blocks"), th("Persisted"))),
              tbody(
                blobs.map { blob =>
                  tr(
                    td(button(cls := "inventory-id", blob.id.value, onClick --> { _ => inspect(blob.id.value) })),
                    td(formatBytes(blob.size)),
                    td(blob.blockCount.toString),
                    td(formatTimestamp(blob.createdAt)),
                  )
                }
              ),
            ),
          )
      },
      child <-- detailsVar.signal.map {
        case None          => emptyNode
        case Some(details) => renderDetails(details, loadingVar, download, verify, delete)
      },
      child <-- verificationVar.signal.map {
        case None         => emptyNode
        case Some(result) =>
          div(
            cls := (if result.verified then "success-message" else "error-message"),
            if result.verified then s"Server verification passed after reading ${formatBytes(result.bytesChecked)}."
            else s"Server verification failed after reading ${formatBytes(result.bytesChecked)}.",
          )
      },
      child <-- errorVar.signal.map {
        case None        => emptyNode
        case Some(error) => div(cls := "error-message", error)
      },
    )

  private def renderDetails(
    details: BlobDetails,
    loading: Var[Boolean],
    download: String => Unit,
    verify: String => Unit,
    delete: String => Unit,
  ): HtmlElement =
    val summary = details.summary
    val id      = summary.id.value
    div(
      cls := "blob-details",
      h3("Persisted manifest"),
      div(cls := "metadata-row", span(cls := "metadata-label", "Content ID"), code(cls := "metadata-value", id)),
      div(cls := "metadata-row", span(cls := "metadata-label", "Digest"), code(cls := "metadata-value", summary.digest)),
      div(cls := "metadata-row", span(cls := "metadata-label", "Size"), span(cls := "metadata-value", formatBytes(summary.size))),
      div(
        cls   := "metadata-row",
        span(cls := "metadata-label", "Persisted"),
        span(cls := "metadata-value", formatTimestamp(summary.createdAt)),
      ),
      div(
        cls   := "operation-actions",
        button(cls := "btn-secondary", "Download", disabled <-- loading.signal, onClick --> { _ => download(id) }),
        button(cls := "btn-primary", "Verify bytes", disabled <-- loading.signal, onClick --> { _ => verify(id) }),
        button(cls := "btn-danger", "Delete manifest", disabled <-- loading.signal, onClick --> { _ => delete(id) }),
      ),
      h4(s"Block layout (${details.blocks.length})"),
      div(
        cls   := "table-scroll manifest-table-wrapper",
        table(
          thead(tr(th("#"), th("Offset"), th("Size"), th("Block content ID"))),
          tbody(
            details.blocks.map { block =>
              tr(
                td(block.index.toString),
                td(formatBytes(block.offset)),
                td(formatBytes(block.size)),
                td(code(block.contentId)),
              )
            }
          ),
        ),
      ),
    )

  private def formatBytes(bytes: Long): String =
    val kib = bytes / 1024.0
    val mib = kib / 1024.0
    val gib = mib / 1024.0
    if gib >= 1 then f"$gib%.2f GiB"
    else if mib >= 1 then f"$mib%.2f MiB"
    else if kib >= 1 then f"$kib%.2f KiB"
    else s"$bytes B"

  private def formatTimestamp(timestamp: Long): String =
    new scala.scalajs.js.Date(timestamp.toDouble).toLocaleString()
