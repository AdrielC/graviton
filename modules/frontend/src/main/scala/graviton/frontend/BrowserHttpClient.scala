package graviton.frontend

import graviton.shared.{HttpClient, MediaTypeText}
import zio.*
import zio.blocks.mediatype.{MediaType, MediaTypes}
import org.scalajs.dom
import scala.scalajs.js
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.JSConverters.*

/** Browser-based HTTP client using Fetch API. Tokens are supplied in memory. */
final case class BrowserHttpClient(
  baseUrl: String,
  bearerToken: () => Option[String] = () => None,
) extends HttpClient {

  private val jsonMediaType: MediaType   = MediaTypes.application.json
  private val binaryMediaType: MediaType = MediaTypes.application.`octet-stream`

  private def headers(contentType: MediaType): js.Dictionary[String] =
    val values = js.Dictionary(
      "Content-Type" -> MediaTypeText.render(contentType),
      "Accept"       -> MediaTypeText.render(jsonMediaType),
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
      init.headers = headers(jsonMediaType)
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
      init.headers = headers(binaryMediaType)
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
      val init        = new dom.RequestInit {}
      init.method = dom.HttpMethod.POST
      val contentType = Option(file.`type`).filter(_.nonEmpty).flatMap(MediaTypeText.parse(_).toOption).getOrElse(binaryMediaType)
      init.headers = headers(contentType)
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
