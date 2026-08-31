package graviton.server.operations

import graviton.protocol.http.HttpSecurityPolicy
import graviton.security.{Capability, ResourceKind, ResourceRef}
import graviton.shared.ApiJson
import zio.*
import zio.http.*
import zio.stream.ZPipeline

/** Authenticated, read-only operator API with a bounded live event source. */
final case class OperationsHttpApi(
  operations: Operations,
  security: Option[HttpSecurityPolicy] = None,
):
  import OperationsHttpApi.*

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "api" / "ops" / "v1" / "snapshot" -> Handler.fromFunctionZIO[Request](snapshot),
    Method.GET / "api" / "ops" / "v1" / "events"   -> Handler.fromFunctionZIO[Request](events),
  )

  private def snapshot(request: Request): UIO[Response] =
    authorize(request, "observability.operations.snapshot") {
      operations.current.map(value => Response.json(ApiJson.encode(value)))
    }

  private def events(request: Request): UIO[Response] =
    authorize(request, "observability.operations.events") {
      val bytes = operations.events
        .map(event => s"id: ${event.sequence}\nevent: snapshot\ndata: ${ApiJson.encode(event)}\n\n")
        .via(ZPipeline.utf8Encode)
      ZIO.succeed(
        Response(
          status = Status.Ok,
          headers = Headers(
            Header.Custom("Content-Type", "text/event-stream; charset=utf-8"),
            Header.Custom("Cache-Control", "no-store"),
            Header.Custom("X-Accel-Buffering", "no"),
            Header.Custom("X-Content-Type-Options", "nosniff"),
          ),
          body = Body.fromStreamChunked(bytes),
        )
      )
    }

  private def authorize(request: Request, action: String)(response: => UIO[Response]): UIO[Response] =
    security match
      case None         => response
      case Some(policy) =>
        policy
          .authorize(request, action, Capability.ObservabilityRead, OperationsResource)
          .foldZIO(
            denied => ZIO.succeed(denied),
            _ => response.flatMap(result => policy.recordOutcome(action, OperationsResource, result).as(result)),
          )

object OperationsHttpApi:
  private val OperationsResource = ResourceRef(ResourceKind.Observability, None)
