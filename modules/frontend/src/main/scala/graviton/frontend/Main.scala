package graviton.frontend

import com.raquo.laminar.api.L.*
import graviton.frontend.components.SchemaExplorerElementRegistry
import org.scalajs.dom
import scala.scalajs.js

/** Application entry point */
object Main {

  def main(args: Array[String]): Unit = {
    SchemaExplorerElementRegistry.ensure()

    // Base URL for API - can be configured via data attribute or environment
    val metaTag = dom.document.querySelector("meta[name=graviton-api-url]")
    val baseUrl = if (metaTag != null) {
      metaTag.asInstanceOf[dom.html.Meta].content
    } else {
      "http://localhost:8080"
    }

    val docsBaseDynamic = dom.window.asInstanceOf[js.Dynamic].selectDynamic("__GRAVITON_DOCS_BASE__")
    val docsBase        =
      if js.isUndefined(docsBaseDynamic) || docsBaseDynamic == null then ""
      else docsBaseDynamic.toString

    def mount(): Unit =
      val container = dom.document.getElementById("graviton-app")
      if container != null then
        val _ = render(container, GravitonApp(baseUrl, docsBase))
        ()
      else dom.console.warn("Graviton demo container not found")

    if dom.document.readyState == "loading" then dom.window.addEventListener("DOMContentLoaded", _ => mount())
    else mount()
  }
}
