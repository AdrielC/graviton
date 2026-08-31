package graviton.server.operations

import graviton.protocol.http.HttpSecurityPolicy
import graviton.security.*
import graviton.shared.ApiJson
import zio.*
import zio.http.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

object OperationsHttpApiSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment, Any] = suite("OperationsHttpApi")(
    test("serves the typed current snapshot without exposing the metric registry") {
      for
        url      <- ZIO.fromEither(URL.decode("http://localhost/api/ops/v1/snapshot"))
        response <- ZIO.scoped(app(Request.get(url)))
        body     <- response.body.asString
        decoded  <- ZIO.fromEither(ApiJson.decode[Operations.Snapshot](body))
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get("Content-Type").exists(_.startsWith("application/json")),
        decoded == Sample,
        !body.contains("MetricKey"),
        !body.contains("tenantId"),
      )
    },
    test("opens a no-buffer event stream with an immediate sequenced snapshot") {
      for
        url                  <- ZIO.fromEither(URL.decode("http://localhost/api/ops/v1/events"))
        result               <- ZIO.scoped {
                                  for
                                    response  <- app(Request.get(url))
                                    firstLine <- response.body.asStream
                                                   .take(64L)
                                                   .takeWhile(_ != '\n'.toByte)
                                                   .runCollect
                                                   .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
                                  yield response -> firstLine
                                }
        (response, firstLine) = result
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get("Content-Type").contains("text/event-stream; charset=utf-8"),
        response.headers.get("Cache-Control").contains("no-store"),
        response.headers.get("X-Accel-Buffering").contains("no"),
        firstLine == "id: 9",
      )
    },
    test("requires observability.read and records operator audit outcomes") {
      val config = SecurityConfig.Default.copy(enabled = true)
      for
        limiter <- ZIO.service[RateLimiter].provide(ZLayer.succeed(config), RateLimiter.live)
        audit   <- AuditSink.inMemory
        policy   = HttpSecurityPolicy.make(config, CapabilityCheck.tokenOnly, limiter, audit)
        secured  = OperationsHttpApi(Operations.fixed(Sample), Some(policy)).routes.toHandler
        url     <- ZIO.fromEither(URL.decode("http://localhost/api/ops/v1/snapshot"))
        denied  <- callAs(secured, context(Capability.BlobRead), Request.get(url))
        allowed <- callAs(secured, context(Capability.ObservabilityRead), Request.get(url))
        events  <- audit.drain
      yield assertTrue(
        denied.status == Status.Forbidden,
        allowed.status == Status.Ok,
        events.exists(event => event.action == "observability.operations.snapshot" && event.outcome == AuditEvent.Outcome.Deny),
        events.exists(event => event.action == "observability.operations.snapshot" && event.outcome == AuditEvent.Outcome.Allow),
      )
    },
  )

  private val Sample = Operations.Snapshot(
    sequence = 9L,
    observedAtEpochMillis = 1234L,
    status = Operations.Status.Ready,
    summary = "All active operational checks are ready",
    checks = List(Operations.Check(Operations.CheckId.Storage, "Storage", Operations.CheckStatus.Ready, "Ready")),
    placement = Operations.Placement(false, Operations.PlacementStatus.SingleNode, None, 0, 0, 0, 1, 0),
    capacity = Operations.Capacity(64L, 64L, 0, false, None, None, None, None, None, None, None, 0L, 0L, 0L),
    durability = Operations.Durability(false, false, 0L, 0L, 0L, 0L, 0L, None),
    dependencies = List.empty,
    traffic = Operations.Traffic(0L, 0L, 0L, 0L, 0L, 0L, 0, 0L, 0L, 0L, 0L),
  )

  private val api                                           = OperationsHttpApi(Operations.fixed(Sample))
  private val app: Handler[Any, Nothing, Request, Response] = api.routes.toHandler

  private def context(capabilities: Capability*): CallerContext =
    CallerContext(
      orgId = UUID.randomUUID(),
      principalId = UUID.randomUUID(),
      capabilities = CapabilitySet.of(capabilities*),
      jti = UUID.randomUUID().toString,
      tokenExpiresAt = Instant.parse("2099-01-01T00:00:00Z"),
      requestId = UUID.randomUUID(),
    )

  private def callAs(
    handler: Handler[Any, Nothing, Request, Response],
    caller: CallerContext,
    request: Request,
  ): Task[Response] =
    CallerContext.scopedWith(caller)(ZIO.scoped(handler(request)))
