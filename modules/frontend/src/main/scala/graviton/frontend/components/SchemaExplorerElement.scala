package graviton.frontend.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.RootNode
import graviton.shared.schema.SchemaExplorer
import org.scalajs.dom
import org.scalajs.dom.CustomElementRegistry
import zio.json.DecoderOps

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
      val data  = Option(getAttribute("data-schema"))
      data.flatMap(parseGraph) match {
        case Some(graph) =>
          root = Some(render(mount, SchemaExplorerView(graph)))
        case None        =>
          mount.textContent = "⚠️ Missing schema payload"
      }
    }

  def disconnectedCallback(): Unit = {
    root.foreach(_.unmount())
    root = None
    while (firstChild != null) {
      val _ = removeChild(firstChild)
    }
  }

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
