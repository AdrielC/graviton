package graviton.frontend

import graviton.shared.*
import graviton.shared.ApiModels.*
import org.scalajs.dom
import zio.*
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
    getJson[BlobListResponse]("/api/v1/blobs")

  def inspectBlob(blobId: String): Task[BlobDetails] =
    getJson[BlobDetails](s"/api/v1/blobs/${encode(blobId)}/metadata")

  def uploadFile(file: dom.File): Task[BlobUploadResult] =
    decode[BlobUploadResult](client.uploadFile("/api/v1/blobs", file))

  def verifyBlob(blobId: String): Task[BlobVerificationResult] =
    decode[BlobVerificationResult](client.post(s"/api/v1/blobs/${encode(blobId)}/verify", ""))

  def deleteBlob(blobId: String): Task[Unit] =
    client.delete(s"/api/v1/blobs/${encode(blobId)}").unit

  def downloadBlob(blobId: String): Task[Unit] =
    client.download(s"/api/v1/blobs/${encode(blobId)}").flatMap { blob =>
      ZIO.attempt {
        val objectUrl = dom.URL.createObjectURL(blob)
        val anchor    = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
        anchor.href = objectUrl
        anchor.download = s"graviton-${blobId.take(24)}.bin"
        anchor.style.display = "none"
        val _         = dom.document.body.appendChild(anchor)
        anchor.click()
        anchor.remove()
        val _         = dom.window.setTimeout(() => dom.URL.revokeObjectURL(objectUrl), 0)
        ()
      }
    }

  private def getJson[A: ApiJsonCodec](path: String): Task[A] =
    decode[A](client.get(path))

  private def decode[A: ApiJsonCodec](effect: Task[String]): Task[A] =
    effect.flatMap(json => ZIO.fromEither(ApiJson.decode[A](json)).mapError(message => new Exception(s"JSON decode error: $message")))

  private def encode(value: String): String =
    js.URIUtils.encodeURIComponent(value)
}

object GravitonApi {
  def layer(baseUrl: String): ULayer[GravitonApi] =
    ZLayer.fromZIO {
      ZIO.succeed(new GravitonApi(baseUrl, new BrowserHttpClient(baseUrl)))
    }
}
