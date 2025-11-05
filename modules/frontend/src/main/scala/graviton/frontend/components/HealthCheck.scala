package graviton.frontend.components

import com.raquo.laminar.api.L.*
import graviton.shared.ApiModels.*
import graviton.frontend.GravitonApi
import zio.*
import scala.concurrent.ExecutionContext.Implicits.global

/** Simple health check component */
object HealthCheck {

  def apply(api: GravitonApi): HtmlElement = {
    val healthVar  = Var[Option[HealthResponse]](None)
    val loadingVar = Var(true)
    val errorVar   = Var[Option[String]](None)

    val runtime = Runtime.default

    def checkHealth(): Unit = {
      loadingVar.set(true)
      errorVar.set(None)

      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.getHealth).onComplete {
          case scala.util.Success(health) =>
            healthVar.set(Some(health))
            loadingVar.set(false)
          case scala.util.Failure(error)  =>
            errorVar.set(Some(error.getMessage))
            loadingVar.set(false)
        }
      }
    }

    // Auto-load on mount
    checkHealth()

    div(
      cls := "health-check",
      child <-- healthVar.signal.map {
        case None         => emptyNode
        case Some(health) =>
          val statusLabel =
            if (health.status.equalsIgnoreCase("demo")) "Demo Mode"
            else health.status

          div(
            cls := "health-status",
            div(
              cls   := s"status-badge status-${health.status.toLowerCase}",
              s"${statusEmoji(health.status)} $statusLabel",
            ),
            div(cls := "health-details", p(s"Version: ${health.version}"), p(s"Uptime: ${formatUptime(health.uptime)}")),
          )
      },
      child <-- errorVar.signal.map {
        case None        => emptyNode
        case Some(error) =>
          div(
            cls := "status-badge status-error",
            s"❌ Offline",
          )
      },
      child <-- loadingVar.signal.map { loading =>
        if (loading) span(cls := "status-loading", "⏳")
        else emptyNode
      },
    )
  }

  private def statusEmoji(status: String): String = status.toLowerCase match {
    case "healthy" | "ok" => "✅"
    case "degraded"       => "⚠️"
    case "demo"           => "🛰️"
    case _                => "❌"
  }

  private def formatUptime(ms: Long): String = {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours   = minutes / 60
    val days    = hours / 24

    if (days > 0) s"${days}d ${hours % 24}h"
    else if (hours > 0) s"${hours}h ${minutes % 60}m"
    else if (minutes > 0) s"${minutes}m ${seconds % 60}s"
    else s"${seconds}s"
  }
}
