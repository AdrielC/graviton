package graviton.shared

import zio.*
import zio.json.*

/** HTTP client interface for Graviton API */
trait HttpClient {
  def get(path: String): Task[String]
  def post(path: String, body: String): Task[String]
  def put(path: String, body: String): Task[String]
  def delete(path: String): Task[String]
}

object HttpClient {

  /** Make a GET request and decode JSON response */
  def getJson[A: JsonDecoder](path: String): ZIO[HttpClient, Throwable, A] =
    for {
      client <- ZIO.service[HttpClient]
      json   <- client.get(path)
      result <- ZIO.fromEither(json.fromJson[A]).mapError(msg => new Exception(s"JSON decode error: $msg"))
    } yield result

  /** Make a POST request with JSON body and decode response */
  def postJson[A: JsonEncoder, B: JsonDecoder](path: String, body: A): ZIO[HttpClient, Throwable, B] =
    for {
      client <- ZIO.service[HttpClient]
      json   <- client.post(path, body.toJson)
      result <- ZIO.fromEither(json.fromJson[B]).mapError(msg => new Exception(s"JSON decode error: $msg"))
    } yield result
}
