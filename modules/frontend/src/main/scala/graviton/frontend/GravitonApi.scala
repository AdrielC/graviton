package graviton.frontend

import graviton.shared.*
import graviton.shared.ApiModels.*
import zio.*

/** High-level API client for Graviton */
class GravitonApi(client: HttpClient) {

  def getHealth: Task[HealthResponse] =
    ZIO.succeed(client).flatMap { c =>
      HttpClient.getJson[HealthResponse]("/api/health").provideEnvironment(ZEnvironment(c))
    }

  def getStats: Task[SystemStats] =
    ZIO.succeed(client).flatMap { c =>
      HttpClient.getJson[SystemStats]("/api/stats").provideEnvironment(ZEnvironment(c))
    }

  def getBlobMetadata(blobId: BlobId): Task[BlobMetadata] =
    ZIO.succeed(client).flatMap { c =>
      HttpClient.getJson[BlobMetadata](s"/api/blobs/${blobId.value}").provideEnvironment(ZEnvironment(c))
    }

  def getBlobManifest(blobId: BlobId): Task[BlobManifest] =
    ZIO.succeed(client).flatMap { c =>
      HttpClient.getJson[BlobManifest](s"/api/blobs/${blobId.value}/manifest").provideEnvironment(ZEnvironment(c))
    }

  def initiateUpload(request: UploadRequest): Task[UploadResponse] =
    ZIO.succeed(client).flatMap { c =>
      HttpClient.postJson[UploadRequest, UploadResponse]("/api/upload", request).provideEnvironment(ZEnvironment(c))
    }
}

object GravitonApi {
  def layer(baseUrl: String): ULayer[GravitonApi] =
    ZLayer.fromZIO {
      ZIO.succeed(new GravitonApi(new BrowserHttpClient(baseUrl)))
    }
}
