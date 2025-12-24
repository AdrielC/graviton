package quasar.frontend.components

import com.raquo.laminar.api.L.*
import quasar.frontend.*
import zio.*

import scala.concurrent.ExecutionContext.Implicits.global

object LegacyImport {

  def apply(api: QuasarApi): HtmlElement = {
    val legacyRepoVar  = Var("shortterm")
    val legacyDocIdVar = Var("doc-1")

    val loadingVar = Var(false)
    val errorVar   = Var[Option[String]](None)
    val resultVar  = Var[Option[LegacyImportResponse]](None)

    val runtime = Runtime.default

    def runImport(): Unit = {
      loadingVar.set(true)
      errorVar.set(None)
      resultVar.set(None)

      val legacyRepo  = legacyRepoVar.now()
      val legacyDocId = legacyDocIdVar.now()

      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.legacyImport(legacyRepo, legacyDocId)).onComplete {
          case scala.util.Success(out) =>
            resultVar.set(Some(out))
            loadingVar.set(false)
          case scala.util.Failure(th)  =>
            errorVar.set(Some(Option(th.getMessage).getOrElse("import failed")))
            loadingVar.set(false)
        }
      }
    }

    div(
      h2("Legacy import"),
      p(
        "This calls ",
        code("POST /v1/legacy/import"),
        " and returns a Quasar document id + the imported blob key.",
      ),
      div(
        cls := "card",
        div(
          cls := "form-row",
          label("Legacy repo"),
          input(
            cls := "input",
            controlled(
              value <-- legacyRepoVar.signal,
              onInput.mapToValue --> legacyRepoVar.writer,
            ),
          ),
        ),
        div(
          cls := "form-row",
          label("Legacy doc id"),
          input(
            cls := "input",
            controlled(
              value <-- legacyDocIdVar.signal,
              onInput.mapToValue --> legacyDocIdVar.writer,
            ),
          ),
        ),
        button(
          cls := "btn btn-primary",
          disabled <-- loadingVar.signal,
          "Import",
          onClick.preventDefault.mapTo(()) --> (_ => runImport()),
        ),
      ),
      child <-- loadingVar.signal.map { loading =>
        if loading then div(cls := "status-loading", "⏳ importing…")
        else emptyNode
      },
      child <-- errorVar.signal.map {
        case None      => emptyNode
        case Some(err) => pre(cls := "error-message", err)
      },
      child <-- resultVar.signal.map {
        case None       => emptyNode
        case Some(resp) =>
          div(
            h3("Result"),
            ul(
              li(b("documentId"), ": ", code(resp.documentId.toString)),
              li(b("blobKey"), ": ", code(resp.blobKey)),
            ),
          )
      },
    )
  }
}
