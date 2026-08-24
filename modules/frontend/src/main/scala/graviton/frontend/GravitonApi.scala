package graviton.frontend

import graviton.shared.*
import graviton.shared.ApiModels.*
import org.scalajs.dom
import zio.*
import zio.json.*
import scala.scalajs.js

/** High-level API client for the live Graviton service. */
final case class GravitonApi(
  baseUrl: String,
  client: BrowserHttpClient,
) {
  def getHealth: Task[HealthResponse] =
    getJson[HealthResponse]("/api/health")

  def getStats: Task[SystemStats] =
    getJson[SystemStats]("/api/stats")

  def listBlobs: Task[BlobListResponse] =
    getJson[BlobListResponse]("/api/blobs")

  def inspectBlob(blobId: String): Task[BlobDetails] =
    getJson[BlobDetails](s"/api/blobs/${encode(blobId)}/metadata")

  def uploadFile(file: dom.File): Task[BlobUploadResult] =
    decode[BlobUploadResult](client.uploadFile("/api/blobs", file))

  def verifyBlob(blobId: String): Task[BlobVerificationResult] =
    decode[BlobVerificationResult](client.post(s"/api/blobs/${encode(blobId)}/verify", ""))

  def deleteBlob(blobId: String): Task[Unit] =
    client.delete(s"/api/blobs/${encode(blobId)}").unit

  def downloadUrl(blobId: String): String =
    s"$baseUrl/api/blobs/${encode(blobId)}"

  private def getJson[A: JsonDecoder](path: String): Task[A] =
    decode[A](client.get(path))

  private def decode[A: JsonDecoder](effect: Task[String]): Task[A] =
    effect.flatMap(json => ZIO.fromEither(json.fromJson[A]).mapError(message => new Exception(s"JSON decode error: $message")))

  private def encode(value: String): String =
    js.URIUtils.encodeURIComponent(value)
}

object GravitonApi {
  def layer(baseUrl: String): ULayer[GravitonApi] =
    ZLayer.fromZIO {
      ZIO.succeed(new GravitonApi(baseUrl, new BrowserHttpClient(baseUrl)))
    }
}
