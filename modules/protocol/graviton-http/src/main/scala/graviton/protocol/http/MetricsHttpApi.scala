package graviton.protocol.http

import graviton.runtime.metrics.{MetricsRegistry, PrometheusTextRenderer}
import zio.*
import zio.http.*

final case class MetricsHttpApi(registry: MetricsRegistry):

  private val metricsHandler: Handler[Any, Nothing, Request, Response] =
    Handler.fromZIO {
      registry.snapshot.map { snap =>
        val body = PrometheusTextRenderer.render(snap)
        Response(
          status = Status.Ok,
          headers = Headers(Header.Custom("Content-Type", "text/plain; version=0.0.4; charset=utf-8")),
          body = Body.fromString(body),
        )
      }
    }

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "metrics" -> metricsHandler
    )
