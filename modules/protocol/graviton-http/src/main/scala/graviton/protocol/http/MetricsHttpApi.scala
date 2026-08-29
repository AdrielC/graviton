package graviton.protocol.http

import graviton.runtime.metrics.{MetricsRegistry, PrometheusTextRenderer}
import graviton.security.{Capability, ResourceKind, ResourceRef}
import zio.*
import zio.http.*

final case class MetricsHttpApi(registry: MetricsRegistry, security: Option[HttpSecurityPolicy] = None):

  private val metricsHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromFunctionZIO[Request] { request =>
      val response = registry.snapshot.map { snap =>
        val body = PrometheusTextRenderer.render(snap)
        Response(
          status = Status.Ok,
          headers = Headers(Header.Custom("Content-Type", "text/plain; version=0.0.4; charset=utf-8")),
          body = Body.fromString(body),
        )
      }
      security match
        case None         => response
        case Some(policy) =>
          val resource = ResourceRef(ResourceKind.Observability, None)
          policy
            .authorize(request, "observability.metrics.read", Capability.ObservabilityRead, resource)
            .foldZIO(
              denied => ZIO.succeed(denied),
              _ => response.flatMap(result => policy.recordOutcome("observability.metrics.read", resource, result).as(result)),
            )
    }

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "metrics" -> metricsHandler
    )
