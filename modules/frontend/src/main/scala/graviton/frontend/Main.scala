package graviton.frontend

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

/** Application entry point */
object Main {

  def main(args: Array[String]): Unit = {
    // Base URL for API - can be configured via data attribute or environment
    val metaTag = dom.document.querySelector("meta[name=graviton-api-url]")
    val baseUrl = if (metaTag != null) {
      metaTag.asInstanceOf[dom.html.Meta].content
    } else {
      "http://localhost:8080"
    }

    // Render app
    renderOnDomContentLoaded(
      dom.document.getElementById("graviton-app"),
      GravitonApp(baseUrl),
    )
  }
}
