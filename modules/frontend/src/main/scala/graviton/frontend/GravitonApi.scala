package graviton.frontend

import com.raquo.laminar.api.L.{Signal, Var}
import graviton.shared.*
import graviton.shared.ApiModels.*
import org.scalajs.dom
import zio.*

/** High-level API client for Graviton with offline/demo fallbacks. */
class GravitonApi(
  baseUrl: String,
  client: HttpClient,
  demoData: DemoData = DemoData.default,
) {

  private val offlineVar = Var(false)

  def offlineSignal: Signal[Boolean] = offlineVar.signal

  def isOffline: Boolean = offlineVar.now()

  def sampleBlobIds: List[BlobId] = demoData.sampleBlobIds

  def getHealth: Task[HealthResponse] =
    withFallback(
      HttpClient.getJson[HealthResponse]("/api/health").provideEnvironment(ZEnvironment(client)),
      Some(demoData.health),
    )

  def getStats: Task[SystemStats] =
    withFallback(
      HttpClient.getJson[SystemStats]("/api/stats").provideEnvironment(ZEnvironment(client)),
      Some(demoData.stats),
    )

  def getBlobMetadata(blobId: BlobId): Task[BlobMetadata] =
    withFallback(
      HttpClient
        .getJson[BlobMetadata](s"/api/blobs/${blobId.value}")
        .provideEnvironment(ZEnvironment(client)),
      demoData.metadataFor(blobId),
      onFallbackMissing = Some(s"Demo dataset does not include blob ${blobId.value}."),
    )

  def getBlobManifest(blobId: BlobId): Task[BlobManifest] =
    withFallback(
      HttpClient
        .getJson[BlobManifest](s"/api/blobs/${blobId.value}/manifest")
        .provideEnvironment(ZEnvironment(client)),
      demoData.manifestFor(blobId),
      onFallbackMissing = Some(s"Demo dataset does not include a manifest for ${blobId.value}."),
    )

  def listSchemas: Task[List[ObjectSchema]] =
    withFallback(
      HttpClient
        .getJson[List[ObjectSchema]]("/api/schema")
        .provideEnvironment(ZEnvironment(client)),
      Some(demoData.schemaCatalog),
    )

  def dashboardStreamUrl: Option[String] =
    val trimmed = baseUrl.trim
    if trimmed.isEmpty then None
    else Some(s"${trimmed.stripSuffix("/")}/api/datalake/dashboard/stream")

  def getDatalakeDashboard: Task[DatalakeDashboardEnvelope] =
    withFallback(
      HttpClient
        .getJson[DatalakeDashboardEnvelope]("/api/datalake/dashboard")
        .provideEnvironment(ZEnvironment(client)),
      Some(DatalakeDashboardEnvelope(demoData.datalakeDashboard, demoData.datalakeMetaschema)),
    )

  def initiateUpload(request: UploadRequest): Task[UploadResponse] =
    withFallback(
      HttpClient
        .postJson[UploadRequest, UploadResponse]("/api/upload", request)
        .provideEnvironment(ZEnvironment(client)),
      Some(demoData.simulateUpload(request)),
    )

  private def withFallback[A](
    effect: Task[A],
    fallback: => Option[A],
    onFallbackMissing: Option[String] = None,
  ): Task[A] =
    effect.catchAll { err =>
      fallback match {
        case Some(value) =>
          markOffline(err)
          ZIO.succeed(value)
        case None        =>
          onFallbackMissing match {
            case Some(message) => ZIO.fail(new Exception(message, err))
            case None          => ZIO.fail(err)
          }
      }
    }

  private def markOffline(cause: Throwable): Unit =
    if (!offlineVar.now()) {
      offlineVar.set(true)
      dom.console.warn("Switching Graviton demo to offline mode", cause.getMessage)
    }
}

object GravitonApi {
  def layer(baseUrl: String): ULayer[GravitonApi] =
    ZLayer.fromZIO {
      ZIO.succeed(new GravitonApi(baseUrl, new BrowserHttpClient(baseUrl)))
    }
}
