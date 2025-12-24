package quasar.frontend

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js

/** Quasar Scala.js entry point (Laminar). */
object Main {

  def main(args: Array[String]): Unit = {
    val metaTag = dom.document.querySelector("meta[name=quasar-api-url]")
    val baseUrl =
      if metaTag != null then metaTag.asInstanceOf[dom.html.Meta].content
      else "http://localhost:8080"

    val docsBaseDynamic = dom.window.asInstanceOf[js.Dynamic].selectDynamic("__GRAVITON_DOCS_BASE__")
    val docsBase        =
      if js.isUndefined(docsBaseDynamic) || docsBaseDynamic == null then ""
      else docsBaseDynamic.toString

    def mount(): Unit =
      val container = dom.document.getElementById("quasar-app")
      if container != null then
        val _ = render(container, QuasarApp(baseUrl, docsBase))
        ()
      else dom.console.warn("Quasar demo container not found")

    if dom.document.readyState == "loading" then dom.window.addEventListener("DOMContentLoaded", _ => mount())
    else mount()
  }
}
