package graviton.frontend.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.RootNode
import graviton.shared.schema.SchemaExplorer
import org.scalajs.dom
import org.scalajs.dom.CustomElementRegistry
import zio.json.DecoderOps

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("GravitonSchemaExplorerElement", "constructor")
class SchemaExplorerElement extends dom.HTMLElement {
  private var root: Option[RootNode] = None

  def connectedCallback(): Unit =
    if (root.isEmpty) {
      val mount = dom.document.createElement("div")
      mount.classList.add("schema-explorer-host")
      val _     = appendChild(mount)
      showLoading(mount)
      loadGraph(mount)
    }

  def disconnectedCallback(): Unit = {
    root.foreach(_.unmount())
    root = None
    while (firstChild != null) {
      val _ = removeChild(firstChild)
    }
  }

  private def loadGraph(mount: dom.Element): Unit = {
    val inline = Option(getAttribute("data-schema")).flatMap(parseGraph)
    inline match {
      case Some(graph) =>
        mountGraph(mount, graph)
      case None        =>
        Option(getAttribute("data-src")) match {
          case Some(url) => fetchGraph(url, mount)
          case None      =>
            showError(mount, "⚠️ Provide either data-schema JSON or data-src to load the explorer.")
        }
    }
  }

  private def fetchGraph(url: String, mount: dom.Element): Unit = {
    val _ = dom
      .fetch(url)
      .toFuture
      .flatMap(_.text().toFuture)
      .map(_.fromJson[SchemaExplorer.Graph])
      .map {
        case Right(graph) => mountGraph(mount, graph)
        case Left(err)    => showError(mount, s"⚠️ Unable to decode schema payload: $err")
      }
      .recover { case e =>
        showError(mount, s"⚠️ Unable to load schema payload: ${e.getMessage}")
      }
  }

  private def mountGraph(mount: dom.Element, graph: SchemaExplorer.Graph): Unit = {
    mount.innerHTML = ""
    root = Some(render(mount, SchemaExplorerView(graph)))
  }

  private def showLoading(mount: dom.Element): Unit =
    mount.innerHTML = "<div class='schema-loading'>Loading schema explorer…</div>"

  private def showError(mount: dom.Element, message: String): Unit =
    mount.innerHTML = s"""<div class="schema-error">$message</div>"""

  private def parseGraph(payload: String): Option[SchemaExplorer.Graph] =
    payload.fromJson[SchemaExplorer.Graph].toOption
}

object SchemaExplorerElementRegistry {
  private var registered = false

  def ensure(): Unit =
    if (!registered) {
      val registry: CustomElementRegistry = dom.window.customElements
      val defined                         = registry.asInstanceOf[js.Dynamic].get("graviton-schema")
      val alreadyDefined                  =
        !js.isUndefined(defined) && defined != null
      if (!alreadyDefined) {
        registry.define("graviton-schema", js.constructorOf[SchemaExplorerElement])
      }
      registered = true
    }
}

private val _ = SchemaExplorerElementRegistry.ensure()
