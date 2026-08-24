package graviton.frontend.components

import com.raquo.laminar.api.L.*
import graviton.frontend.GravitonApi
import graviton.shared.ApiModels.*
import org.scalajs.dom
import zio.*

import scala.concurrent.ExecutionContext.Implicits.global

/** Uploads the selected file to the configured Graviton server. */
object FileUpload:

  def apply(api: GravitonApi): HtmlElement =
    val selectedVar     = Var[Option[dom.File]](None)
    val resultVar       = Var[Option[BlobUploadResult]](None)
    val verificationVar = Var[Option[BlobVerificationResult]](None)
    val uploadingVar    = Var(false)
    val verifyingVar    = Var(false)
    val errorVar        = Var[Option[String]](None)
    val runtime         = Runtime.default

    def upload(): Unit =
      selectedVar.now() match
        case None       => errorVar.set(Some("Choose a non-empty file first."))
        case Some(file) =>
          if file.size <= 0 then errorVar.set(Some("Graviton rejects empty blobs."))
          else
            uploadingVar.set(true)
            resultVar.set(None)
            verificationVar.set(None)
            errorVar.set(None)
            Unsafe.unsafe { implicit unsafe =>
              runtime.unsafe.runToFuture(api.uploadFile(file)).onComplete {
                case scala.util.Success(result) =>
                  resultVar.set(Some(result))
                  uploadingVar.set(false)
                case scala.util.Failure(error)  =>
                  errorVar.set(Some(error.getMessage))
                  uploadingVar.set(false)
              }
            }

    def verify(blobId: String): Unit =
      verifyingVar.set(true)
      verificationVar.set(None)
      errorVar.set(None)
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.verifyBlob(blobId)).onComplete {
          case scala.util.Success(result) =>
            verificationVar.set(Some(result))
            verifyingVar.set(false)
          case scala.util.Failure(error)  =>
            errorVar.set(Some(error.getMessage))
            verifyingVar.set(false)
        }
      }

    div(
      cls := "file-upload",
      h2("Upload real bytes"),
      p(
        cls   := "page-intro",
        "The selected file is streamed to the configured Graviton server. Every result below comes from the completed CAS ingest.",
      ),
      div(cls := "connection-target", span("Server"), code(api.baseUrl)),
      div(
        cls   := "upload-area",
        input(
          tpe := "file",
          cls := "file-input",
          onChange --> { event =>
            val files = event.target.asInstanceOf[dom.HTMLInputElement].files
            val file  = Option(files).filter(_.length > 0).flatMap(list => Option(list.item(0)))
            selectedVar.set(file)
            resultVar.set(None)
            verificationVar.set(None)
            errorVar.set(None)
          },
        ),
        button(
          cls := "btn-primary",
          child.text <-- uploadingVar.signal.map(if _ then "Uploading..." else "Upload to Graviton"),
          disabled <-- selectedVar.signal.combineWith(uploadingVar.signal).map { case (file, uploading) => file.isEmpty || uploading },
          onClick --> { _ => upload() },
        ),
      ),
      child <-- selectedVar.signal.map {
        case None       => div(cls := "stats-empty", "No file selected")
        case Some(file) =>
          div(
            cls := "selected-file",
            strong(file.name),
            span(formatBytes(file.size.toLong)),
            span(Option(file.`type`).filter(_.nonEmpty).getOrElse("application/octet-stream")),
          )
      },
      child <-- resultVar.signal.map {
        case None         => emptyNode
        case Some(result) =>
          val id = result.blob.id.value
          div(
            cls := "blob-details upload-result",
            h3("Persisted"),
            div(cls := "metadata-row", span(cls := "metadata-label", "Content ID"), code(cls := "metadata-value", id)),
            div(
              cls   := "metadata-row",
              span(cls := "metadata-label", "Bytes"),
              span(cls := "metadata-value", formatBytes(result.blob.size)),
            ),
            div(
              cls   := "metadata-row",
              span(cls := "metadata-label", "Blocks"),
              span(cls := "metadata-value", result.blob.blockCount.toString),
            ),
            div(
              cls   := "metadata-row",
              span(cls := "metadata-label", "Fresh blocks"),
              span(cls := "metadata-value", result.freshBlocks.toString),
            ),
            div(
              cls   := "metadata-row",
              span(cls := "metadata-label", "Duplicate blocks"),
              span(cls := "metadata-value", result.duplicateBlocks.toString),
            ),
            div(
              cls   := "metadata-row",
              span(cls := "metadata-label", "Server ingest"),
              span(cls := "metadata-value", f"${result.durationSeconds}%.3f s"),
            ),
            div(
              cls   := "operation-actions",
              a(cls := "btn-secondary", href := api.downloadUrl(id), download := "", "Download stored bytes"),
              button(
                cls := "btn-primary",
                child.text <-- verifyingVar.signal.map(if _ then "Verifying..." else "Verify on server"),
                disabled <-- verifyingVar.signal,
                onClick --> { _ => verify(id) },
              ),
            ),
          )
      },
      child <-- verificationVar.signal.map {
        case None         => emptyNode
        case Some(result) =>
          div(
            cls := (if result.verified then "success-message" else "error-message"),
            if result.verified then s"Verified ${formatBytes(result.bytesChecked)} against the content ID."
            else s"Verification failed after reading ${formatBytes(result.bytesChecked)}.",
          )
      },
      child <-- errorVar.signal.map {
        case None        => emptyNode
        case Some(error) => div(cls := "error-message", error)
      },
    )

  private def formatBytes(bytes: Long): String =
    val kib = bytes / 1024.0
    val mib = kib / 1024.0
    val gib = mib / 1024.0
    if gib >= 1 then f"$gib%.2f GiB"
    else if mib >= 1 then f"$mib%.2f MiB"
    else if kib >= 1 then f"$kib%.2f KiB"
    else s"$bytes B"
