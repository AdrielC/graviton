package graviton.frontend

import graviton.shared.HttpClient
import zio.*
import org.scalajs.dom
import scala.scalajs.js
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.JSConverters.*

/** Browser-based HTTP client using Fetch API */
final case class BrowserHttpClient(baseUrl: String) extends HttpClient {

  private def fetch(path: String, method: String, body: Option[String] = None): Task[String] = {
    val url = s"$baseUrl$path"

    ZIO.fromPromiseJS {
      val init = new dom.RequestInit {}
      init.method = method match {
        case "GET"    => dom.HttpMethod.GET
        case "POST"   => dom.HttpMethod.POST
        case "PUT"    => dom.HttpMethod.PUT
        case "DELETE" => dom.HttpMethod.DELETE
        case _        => dom.HttpMethod.GET
      }
      init.headers = js.Dictionary(
        "Content-Type" -> "application/json",
        "Accept"       -> "application/json",
      )
      body.foreach(b => init.body = b)

      dom
        .fetch(url, init)
        .toFuture
        .flatMap { response =>
          response.text().toFuture.map { text =>
            if (response.ok) text
            else throw new Exception(s"HTTP ${response.status}: $text")
          }
        }
        .toJSPromise
    }
  }

  def get(path: String): Task[String] = fetch(path, "GET")

  def post(path: String, body: String): Task[String] = fetch(path, "POST", Some(body))

  def put(path: String, body: String): Task[String] = fetch(path, "PUT", Some(body))

  def delete(path: String): Task[String] = fetch(path, "DELETE")
}

object BrowserHttpClient {
  def layer(baseUrl: String): ULayer[HttpClient] =
    ZLayer.succeed(new BrowserHttpClient(baseUrl))
}
