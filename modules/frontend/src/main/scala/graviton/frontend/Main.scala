package graviton.frontend

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js

/** Application entry point */
object Main {

  def main(args: Array[String]): Unit = {
    // The operations console never falls back to local data. It must have a real API endpoint.
    val metaTag   = dom.document.querySelector("meta[name=graviton-api-url]")
    val metaUrl   = if metaTag != null then metaTag.asInstanceOf[dom.html.Meta].content else "http://localhost:8081"
    val queryUrl  = dom.window.location.search
      .stripPrefix("?")
      .split("&")
      .iterator
      .flatMap { entry =>
        entry.split("=", 2).toList match
          case "api" :: value :: Nil => Some(js.URIUtils.decodeURIComponent(value))
          case _                     => None
      }
      .toSeq
      .headOption
    val storedUrl = Option(dom.window.localStorage.getItem("graviton.apiUrl")).filter(_.nonEmpty)
    val baseUrl   = queryUrl.orElse(storedUrl).getOrElse(metaUrl).stripSuffix("/")
    dom.window.localStorage.setItem("graviton.apiUrl", baseUrl)

    val docsBaseDynamic = dom.window.asInstanceOf[js.Dynamic].selectDynamic("__GRAVITON_DOCS_BASE__")
    val docsBase        =
      if js.isUndefined(docsBaseDynamic) || docsBaseDynamic == null then ""
      else docsBaseDynamic.toString

    def mount(): Unit =
      val container = dom.document.getElementById("graviton-app")
      if container != null then
        val _ = render(container, GravitonApp(baseUrl, docsBase))
        ()
      else dom.console.warn("Graviton operations console container not found")

    if dom.document.readyState == "loading" then dom.window.addEventListener("DOMContentLoaded", _ => mount())
    else mount()
  }
}
