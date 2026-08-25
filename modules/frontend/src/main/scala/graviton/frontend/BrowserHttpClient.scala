package graviton.frontend

import graviton.shared.HttpClient
import zio.*
import org.scalajs.dom
import scala.scalajs.js
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.JSConverters.*

/** Browser-based HTTP client using Fetch API. Tokens are supplied in memory. */
final case class BrowserHttpClient(
  baseUrl: String,
  bearerToken: () => Option[String] = () => None,
) extends HttpClient {

  private def headers(contentType: String): js.Dictionary[String] =
    val values = js.Dictionary(
      "Content-Type" -> contentType,
      "Accept"       -> "application/json",
    )
    bearerToken().map(_.trim).filter(_.nonEmpty).foreach(token => values.update("Authorization", s"Bearer $token"))
    values

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
      init.headers = headers("application/json")
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

  def download(path: String): Task[dom.Blob] =
    ZIO.fromPromiseJS {
      val init = new dom.RequestInit {}
      init.method = dom.HttpMethod.GET
      init.headers = headers("application/octet-stream")
      dom
        .fetch(s"$baseUrl$path", init)
        .toFuture
        .flatMap { response =>
          if response.ok then response.blob().toFuture
          else response.text().toFuture.flatMap(text => scala.concurrent.Future.failed(new Exception(s"HTTP ${response.status}: $text")))
        }
        .toJSPromise
    }

  /** Stream the selected browser file directly to the real blob endpoint. */
  def uploadFile(path: String, file: dom.File): Task[String] = {
    val url = s"$baseUrl$path"

    ZIO.fromPromiseJS {
      val init = new dom.RequestInit {}
      init.method = dom.HttpMethod.POST
      init.headers = headers(Option(file.`type`).filter(_.nonEmpty).getOrElse("application/octet-stream"))
      init.body = file

      dom
        .fetch(url, init)
        .toFuture
        .flatMap { response =>
          response.text().toFuture.map { text =>
            if response.ok then text
            else throw new Exception(s"HTTP ${response.status}: $text")
          }
        }
        .toJSPromise
    }
  }
}

object BrowserHttpClient {
  def layer(baseUrl: String): ULayer[HttpClient] =
    ZLayer.succeed(new BrowserHttpClient(baseUrl))
}
