package quasar.frontend.components

import com.raquo.laminar.api.L.*
import quasar.frontend.QuasarApi
import zio.*

import scala.concurrent.ExecutionContext.Implicits.global

object HealthCheck {

  def apply(api: QuasarApi): HtmlElement = {
    val okVar      = Var[Option[Boolean]](None)
    val loadingVar = Var(true)

    val runtime = Runtime.default

    def refresh(): Unit = {
      loadingVar.set(true)
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.health).onComplete {
          case scala.util.Success(ok) =>
            okVar.set(Some(ok))
            loadingVar.set(false)
          case scala.util.Failure(_)  =>
            okVar.set(Some(false))
            loadingVar.set(false)
        }
      }
    }

    refresh()

    div(
      cls := "health-check",
      button(
        cls := "btn btn-secondary",
        "↻",
        onClick.preventDefault.mapTo(()) --> (_ => refresh()),
      ),
      child <-- loadingVar.signal.map { loading =>
        if loading then span(cls := "status-loading", "⏳")
        else emptyNode
      },
      child <-- okVar.signal.map {
        case None        => emptyNode
        case Some(true)  => span(cls := "status-badge status-healthy", "✅ ok")
        case Some(false) => span(cls := "status-badge status-error", "❌ offline")
      },
    )
  }
}
