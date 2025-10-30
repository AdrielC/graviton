package graviton.frontend.components

import com.raquo.laminar.api.L.{*, given}
import graviton.shared.ApiModels.*
import graviton.frontend.GravitonApi
import zio.*
import scala.concurrent.ExecutionContext.Implicits.global

/** Interactive stats panel showing system metrics */
object StatsPanel {

  private def formatBytes(bytes: Long): String = {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0

    if (gb >= 1) f"$gb%.2f GB"
    else if (mb >= 1) f"$mb%.2f MB"
    else if (kb >= 1) f"$kb%.2f KB"
    else s"$bytes B"
  }

  def apply(api: GravitonApi): HtmlElement = {
    val statsVar   = Var[Option[SystemStats]](None)
    val loadingVar = Var(false)
    val errorVar   = Var[Option[String]](None)

    val runtime = Runtime.default

    def loadStats(): Unit = {
      loadingVar.set(true)
      errorVar.set(None)

      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.runToFuture(api.getStats).onComplete {
          case scala.util.Success(stats) =>
            statsVar.set(Some(stats))
            loadingVar.set(false)
          case scala.util.Failure(error) =>
            errorVar.set(Some(error.getMessage))
            loadingVar.set(false)
        }
      }
    }

    div(
      cls := "stats-panel",
      h2("📊 System Statistics"),
      div(
        cls := "stats-controls",
        button(
          cls := "btn-primary",
          "🔄 Refresh Stats",
          onClick --> { _ => loadStats() },
          disabled <-- loadingVar.signal,
        ),
      ),
      child <-- statsVar.signal.map {
        case None =>
          div(cls := "stats-empty", "Click refresh to load statistics")

        case Some(stats) =>
          div(
            cls := "stats-grid",
            div(
              cls := "stat-card",
              div(cls := "stat-label", "Total Blobs"),
              div(cls := "stat-value", stats.totalBlobs.toString),
              div(cls := "stat-icon", "📦"),
            ),
            div(
              cls := "stat-card",
              div(cls := "stat-label", "Total Storage"),
              div(cls := "stat-value", formatBytes(stats.totalBytes)),
              div(cls := "stat-icon", "💾"),
            ),
            div(
              cls := "stat-card",
              div(cls := "stat-label", "Unique Chunks"),
              div(cls := "stat-value", stats.uniqueChunks.toString),
              div(cls := "stat-icon", "🧩"),
            ),
            div(
              cls := "stat-card",
              div(cls := "stat-label", "Dedup Ratio"),
              div(cls := "stat-value", f"${stats.deduplicationRatio}%.2f:1"),
              div(cls := "stat-icon", "📈"),
            ),
          )
      },
      child <-- errorVar.signal.map {
        case None        => emptyNode
        case Some(error) =>
          div(
            cls := "error-message",
            s"⚠️ Error: $error",
          )
      },
      child <-- loadingVar.signal.map { loading =>
        if (loading) div(cls := "loading-spinner", "⏳ Loading...")
        else emptyNode
      },
    )
  }
}
