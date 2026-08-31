package graviton.integration.redis

import graviton.runtime.admission.*
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.stores.TransferScope
import graviton.runtime.upload.TenantId
import zio.*
import zio.redis.{CodecSupplier, Input, Output, Redis, RedisError}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Redis or Valkey implementation of cluster-wide hierarchical transfer
 * admission. A single static Lua script owns all state transitions and emits a
 * bounded Redis Stream of operational events in the same atomic step.
 */
final class RedisDistributedAdmission private[redis] (
  redis: Redis,
  config: RedisAdmissionConfig,
  scriptSha: Ref[String],
  metrics: MetricsRegistry,
) extends DistributedAdmission,
      DistributedAdmissionControl:

  import RedisDistributedAdmission.*

  override def acquireScoped(
    request: DistributedAdmissionRequest
  ): ZIO[Scope, DistributedAdmission.Error, DistributedAdmissionLease] =
    for
      _         <- validateRequest(request)
      leaseId   <- newLeaseId
      tenantKey  = request.scope.tenantId.fold("-")(hashTenant)
      backendKey = hash(request.scope.backend.value)
      started   <- Clock.nanoTime
      admitted  <- acquireUntilDeadline(request, leaseId, tenantKey, backendKey, started, firstRejection = true)
                     .tapBoth(
                       error =>
                         metrics.counter(
                           MetricKeys.DistributedAdmissionTotal,
                           Map("outcome" -> outcomeOf(error), "operation" -> request.operation.toString),
                         ),
                       _ =>
                         metrics.counter(
                           MetricKeys.DistributedAdmissionTotal,
                           Map("outcome" -> "admitted", "operation" -> request.operation.toString),
                         ),
                     )
      lost      <- Promise.make[DistributedAdmission.Error, Nothing]
      deadline  <- Ref.make(admitted.safeDeadlineNanos)
      lease      = LiveLease(
                     leaseId,
                     admitted.fencingToken,
                     admitted.policyVersion,
                     admitted.occupancy,
                     lost.await,
                   )
      _         <- ZIO.addFinalizerExit(exit => release(lease, exit).ignore)
      _         <- renewalLoop(lease, deadline).catchAll(error => lost.fail(error).unit).forkScoped
      waited    <- Clock.nanoTime.map(now => math.max(0L, now - started))
      _         <- recordMetrics(request, admitted.occupancy, waited)
    yield lease

  override def snapshot(scope: TransferScope): IO[DistributedAdmission.Error, AdmissionSnapshot] =
    val tenantKey  = scope.tenantId.fold("-")(hashTenant)
    val backendKey = hash(scope.backend.value)
    execute("snapshot", Chunk(tenantKey, backendKey)).flatMap(parseSnapshot(scope))

  override def setTenantOverride(
    tenantId: TenantId,
    value: TenantAdmissionOverride,
  ): IO[DistributedAdmission.Error, AdmissionPolicyVersion] =
    ZIO
      .fromEither(value.validate(config.limits))
      .mapError(DistributedAdmission.Error.InvalidRequest.apply)
      .zipRight(
        execute(
          "policy",
          Chunk(
            hashTenant(tenantId),
            value.maximumBufferedBytes.fold("-")(_.value.toString),
            value.maximumConcurrentTransfers.fold("-")(_.value.toString),
          ),
        ).flatMap(parsePolicy)
      )

  override def clearTenantOverride(tenantId: TenantId): IO[DistributedAdmission.Error, AdmissionPolicyVersion] =
    setTenantOverride(tenantId, TenantAdmissionOverride())

  private[redis] def eventCount: IO[DistributedAdmission.Error, Long] =
    redis.xLen[String](redisKeys(5)).mapError(redisError)

  private def acquireUntilDeadline(
    request: DistributedAdmissionRequest,
    leaseId: AdmissionLeaseId,
    tenantKey: String,
    backendKey: String,
    startedNanos: Long,
    firstRejection: Boolean,
  ): IO[DistributedAdmission.Error, Admitted] =
    attemptAcquire(request, leaseId, tenantKey, backendKey).flatMap {
      case Right(admitted) => ZIO.succeed(admitted)
      case Left(rejected)  =>
        for
          now      <- Clock.nanoTime
          elapsed   = math.max(0L, now - startedNanos)
          remaining = config.acquisitionTimeout.toNanos - elapsed
          _        <- ZIO.when(firstRejection)(
                        recordEvent(
                          "queued",
                          request,
                          tenantKey,
                          backendKey,
                          rejected.policyVersion,
                          rejected.dimension.toString,
                        ).ignore
                      )
          result   <- (
                        if remaining <= 0L then
                          recordEvent(
                            "timed_out",
                            request,
                            tenantKey,
                            backendKey,
                            rejected.policyVersion,
                            rejected.dimension.toString,
                          ).ignore *>
                            ZIO.fail(DistributedAdmission.Error.TimedOut(config.acquisitionTimeout))
                        else
                          jitteredDelay(remaining, rejected.retryAfter).flatMap(delay =>
                            ZIO.sleep(Duration.fromNanos(delay)) *>
                              acquireUntilDeadline(request, leaseId, tenantKey, backendKey, startedNanos, firstRejection = false)
                          )
                      )
        yield result
    }

  private def attemptAcquire(
    request: DistributedAdmissionRequest,
    leaseId: AdmissionLeaseId,
    tenantKey: String,
    backendKey: String,
  ): IO[DistributedAdmission.Error, Either[Rejected, Admitted]] =
    val limits = config.limits
    for
      requestedAt <- Clock.nanoTime
      result      <- execute(
                       "acquire",
                       Chunk(
                         leaseId.value,
                         tenantKey,
                         backendKey,
                         request.footprint.totalBytes.toString,
                         limits.maximumServiceBufferedBytes.value.toString,
                         limits.maximumConcurrentServiceTransfers.value.toString,
                         limits.maximumTenantBufferedBytes.value.toString,
                         limits.maximumConcurrentTenantTransfers.value.toString,
                         limits.maximumConcurrentBackendTransfers.value.toString,
                         config.leaseTtl.toMillis.toString,
                         config.retryInterval.toMillis.toString,
                         request.operation.toString,
                       ),
                     ).flatMap(parseAcquire)
    yield result.map(_.copy(safeDeadlineNanos = saturatingAdd(requestedAt, config.leaseTtl.toNanos)))

  private def renewalLoop(
    lease: LiveLease,
    deadlineNanos: Ref[Long],
  ): IO[DistributedAdmission.Error, Nothing] =
    def renewUntilDeadline: IO[DistributedAdmission.Error, Long] =
      for
        now      <- Clock.nanoTime
        deadline <- deadlineNanos.get
        _        <- ZIO
                      .fail(DistributedAdmission.Error.LeaseLost(lease.id))
                      .when(
                        saturatingAdd(now, config.renewalInterval.toNanos) >= deadline
                      )
        result   <- renewOnce(lease).either
        renewed  <- result match
                      case Right(safeDeadline)                               => ZIO.succeed(safeDeadline)
                      case Left(error: DistributedAdmission.Error.LeaseLost) => ZIO.fail(error)
                      case Left(_)                                           => ZIO.sleep(config.retryInterval) *> renewUntilDeadline
      yield renewed

    (ZIO.sleep(config.renewalInterval) *>
      renewOnce(lease)
        .catchAll {
          case error: DistributedAdmission.Error.LeaseLost => ZIO.fail(error)
          case _                                           => renewUntilDeadline
        }
        .flatMap(deadlineNanos.set)).forever

  private def renewOnce(lease: LiveLease): IO[DistributedAdmission.Error, Long] =
    for
      requestedAt <- Clock.nanoTime
      result      <- execute(
                       "renew",
                       Chunk(lease.id.value, lease.fencingToken.value.toString, config.leaseTtl.toMillis.toString),
                     ).flatMap {
                       case response if response.startsWith("RENEWED|") =>
                         ZIO.succeed(saturatingAdd(requestedAt, config.leaseTtl.toNanos))
                       case "LOST" | "STALE"                            => ZIO.fail(DistributedAdmission.Error.LeaseLost(lease.id))
                       case other                                       =>
                         ZIO.fail(DistributedAdmission.Error.Protocol(s"unexpected renew response '$other'"))
                     }
    yield result

  private def release(lease: LiveLease, exit: Exit[Any, Any]): UIO[Unit] =
    val outcome = exit match
      case Exit.Success(_)                                => "completed"
      case Exit.Failure(cause) if cause.isInterruptedOnly => "interrupted"
      case Exit.Failure(_)                                => "failed"
    execute("release", Chunk(lease.id.value, lease.fencingToken.value.toString, outcome))
      .tapError(error => ZIO.logError(error.getMessage))
      .ignore

  private def recordEvent(
    kind: String,
    request: DistributedAdmissionRequest,
    tenantKey: String,
    backendKey: String,
    policyVersion: AdmissionPolicyVersion,
    outcome: String,
  ): IO[DistributedAdmission.Error, Unit] =
    execute(
      "event",
      Chunk(
        kind,
        tenantKey,
        backendKey,
        request.footprint.totalBytes.toString,
        request.operation.toString,
        policyVersion.value.toString,
        outcome,
      ),
    ).flatMap {
      case "RECORDED" => ZIO.unit
      case other      => ZIO.fail(DistributedAdmission.Error.Protocol(s"unexpected event response '$other'"))
    }

  private def execute(action: String, arguments: Chunk[String]): IO[DistributedAdmission.Error, String] =
    val args                                     = Chunk(action, config.maximumEvents.toString, config.maximumExpiredLeasesPerPass.toString) ++ arguments
    def run(sha: String): IO[RedisError, String] =
      redis
        .evalSha[String, String](sha, redisKeys, args)(using Input.StringInput, Input.StringInput)
        .returning[String](using Output.MultiStringOutput)

    scriptSha.get
      .flatMap(run)
      .catchSome { case _: RedisError.NoScript =>
        redis.scriptLoad(RedisAdmissionScripts.Coordinator).flatMap(sha => scriptSha.set(sha) *> run(sha))
      }
      .mapError(redisError)

  private def redisKeys: Chunk[String] =
    RedisDistributedAdmission.keys(config)

  private def validateRequest(request: DistributedAdmissionRequest): IO[DistributedAdmission.Error, Unit] =
    if request.footprint.totalBytes <= 0L then
      ZIO.fail(DistributedAdmission.Error.InvalidRequest("distributed admission requires a positive transfer footprint"))
    else if request.footprint.totalBytes > config.limits.maximumServiceBufferedBytes.value then
      ZIO.fail(DistributedAdmission.Error.Rejected(AdmissionDimension.ServiceBytes, config.retryInterval))
    else ZIO.unit

  private def parseAcquire(value: String): IO[DistributedAdmission.Error, Either[Rejected, Admitted]] =
    val fields = value.split("\\|", -1).toVector
    fields.headOption match
      case Some("ADMITTED") if fields.length == 9 =>
        for
          token     <- parseLong(fields(1), "fencing token").flatMap(refineToken)
          _         <- parseLong(fields(2), "lease expiry")
          version   <- parseLong(fields(3), "policy version").flatMap(refineVersion)
          occupancy <- parseOccupancy(fields, offset = 4)
        yield Right(Admitted(token, version, occupancy))
      case Some("REJECTED") if fields.length == 9 =>
        for
          dimension <- parseDimension(fields(1))
          retryMs   <- parseLong(fields(2), "retry delay")
          occupancy <- parseOccupancy(fields, offset = 3)
          version   <- parseLong(fields(8), "policy version").flatMap(refineVersion)
        yield Left(Rejected(dimension, Duration.fromMillis(math.max(1L, retryMs)), version, occupancy))
      case Some("PROTOCOL")                       =>
        ZIO.fail(DistributedAdmission.Error.Protocol(fields.drop(1).mkString("|")))
      case _                                      =>
        ZIO.fail(DistributedAdmission.Error.Protocol(s"unexpected acquire response '$value'"))

  private def parseSnapshot(scope: TransferScope)(value: String): IO[DistributedAdmission.Error, AdmissionSnapshot] =
    val fields = value.split("\\|", -1).toVector
    if fields.length != 8 || fields.head != "SNAPSHOT" then
      ZIO.fail(DistributedAdmission.Error.Protocol(s"unexpected snapshot response '$value'"))
    else
      for
        occupancy <- parseOccupancy(fields, offset = 1)
        version   <- parseLong(fields(6), "policy version").flatMap(refineVersion)
        observed  <- parseLong(fields(7), "observed timestamp")
      yield AdmissionSnapshot(scope, occupancy, version, observed)

  private def parsePolicy(value: String): IO[DistributedAdmission.Error, AdmissionPolicyVersion] =
    value.split("\\|", -1).toVector match
      case Vector("POLICY", raw) => parseLong(raw, "policy version").flatMap(refineVersion)
      case _                     => ZIO.fail(DistributedAdmission.Error.Protocol(s"unexpected policy response '$value'"))

  private def parseOccupancy(fields: Vector[String], offset: Int): IO[DistributedAdmission.Error, AdmissionOccupancy] =
    for
      serviceBytes     <- parseLong(fields(offset), "service bytes")
      serviceTransfers <- parseLong(fields(offset + 1), "service transfers")
      tenantBytes      <- parseOptionalOccupancy(fields(offset + 2), "tenant bytes")
      tenantTransfers  <- parseOptionalOccupancy(fields(offset + 3), "tenant transfers")
      backendTransfers <- parseLong(fields(offset + 4), "backend transfers")
    yield AdmissionOccupancy(serviceBytes, serviceTransfers, tenantBytes, tenantTransfers, backendTransfers)

  private def parseOptionalOccupancy(value: String, label: String): IO[DistributedAdmission.Error, Option[Long]] =
    if value == "-1" then ZIO.none else parseLong(value, label).map(Some(_))

  private def parseDimension(value: String): IO[DistributedAdmission.Error, AdmissionDimension] = value match
    case "service_bytes"     => ZIO.succeed(AdmissionDimension.ServiceBytes)
    case "service_transfers" => ZIO.succeed(AdmissionDimension.ServiceTransfers)
    case "tenant_bytes"      => ZIO.succeed(AdmissionDimension.TenantBytes)
    case "tenant_transfers"  => ZIO.succeed(AdmissionDimension.TenantTransfers)
    case "backend_transfers" => ZIO.succeed(AdmissionDimension.BackendTransfers)
    case other               => ZIO.fail(DistributedAdmission.Error.Protocol(s"unknown admission dimension '$other'"))

  private def parseLong(value: String, label: String): IO[DistributedAdmission.Error, Long] =
    ZIO.attempt(value.toLong).mapError(_ => DistributedAdmission.Error.Protocol(s"invalid $label"))

  private def refineToken(value: Long): IO[DistributedAdmission.Error, AdmissionFencingToken] =
    ZIO.fromEither(AdmissionFencingToken.either(value)).mapError(DistributedAdmission.Error.Protocol.apply)

  private def refineVersion(value: Long): IO[DistributedAdmission.Error, AdmissionPolicyVersion] =
    ZIO.fromEither(AdmissionPolicyVersion.either(value)).mapError(DistributedAdmission.Error.Protocol.apply)

  private def recordMetrics(
    request: DistributedAdmissionRequest,
    occupancy: AdmissionOccupancy,
    waitNanos: Long,
  ): UIO[Unit] =
    val tags = Map("backend" -> request.scope.backend.value, "operation" -> request.operation.toString)
    metrics.histogram(MetricKeys.DistributedAdmissionWait, waitNanos.toDouble / 1000000000.0, tags) *>
      metrics.gauge(MetricKeys.DistributedAdmissionServiceBytes, occupancy.serviceBufferedBytes.toDouble, Map.empty) *>
      metrics.gauge(MetricKeys.DistributedAdmissionServiceTransfers, occupancy.serviceTransfers.toDouble, Map.empty) *>
      metrics.gauge(
        MetricKeys.DistributedAdmissionBackendTransfers,
        occupancy.backendTransfers.toDouble,
        Map("backend" -> request.scope.backend.value),
      )

  private def newLeaseId: UIO[AdmissionLeaseId] =
    Random.nextUUID.map(uuid => AdmissionLeaseId.applyUnsafe(uuid.toString))

  private def hashTenant(tenantId: TenantId): String = hash(tenantId.value)

  private def hash(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    HexFormat.of().formatHex(digest)

  private def redisError(error: RedisError): DistributedAdmission.Error =
    DistributedAdmission.Error.Unavailable(error.getClass.getSimpleName)

  private def outcomeOf(error: DistributedAdmission.Error): String = error match
    case _: DistributedAdmission.Error.Rejected       => "rejected"
    case _: DistributedAdmission.Error.TimedOut       => "timed_out"
    case _: DistributedAdmission.Error.Unavailable    => "unavailable"
    case _: DistributedAdmission.Error.LeaseLost      => "lease_lost"
    case _: DistributedAdmission.Error.InvalidRequest => "invalid"
    case _: DistributedAdmission.Error.Protocol       => "protocol_error"

  private def saturatingAdd(left: Long, right: Long): Long =
    if right > 0L && left > Long.MaxValue - right then Long.MaxValue else left + right

  private def jitteredDelay(remainingNanos: Long, retryAfter: Duration): UIO[Long] =
    val base      = math.min(remainingNanos, math.max(1L, retryAfter.toNanos))
    val maxJitter = math.min(base, math.max(0L, remainingNanos - base))
    if maxJitter == 0L then ZIO.succeed(base)
    else Random.nextLongBounded(maxJitter).map(jitter => base + jitter)

object RedisDistributedAdmission:
  private[redis] def keys(config: RedisAdmissionConfig): Chunk[String] =
    val slot = s"${config.keyPrefix}:{${config.cellId.value}}:admission"
    Chunk(
      s"$slot:counts",
      s"$slot:expirations",
      s"$slot:leases",
      s"$slot:fence",
      s"$slot:policy",
      s"$slot:events",
    )

  private final case class Rejected(
    dimension: AdmissionDimension,
    retryAfter: Duration,
    policyVersion: AdmissionPolicyVersion,
    occupancy: AdmissionOccupancy,
  )
  private final case class Admitted(
    fencingToken: AdmissionFencingToken,
    policyVersion: AdmissionPolicyVersion,
    occupancy: AdmissionOccupancy,
    safeDeadlineNanos: Long = 0L,
  )

  private final case class LiveLease(
    override val id: AdmissionLeaseId,
    override val fencingToken: AdmissionFencingToken,
    override val policyVersion: AdmissionPolicyVersion,
    override val occupancyAtAdmission: AdmissionOccupancy,
    override val revoked: IO[DistributedAdmission.Error, Nothing],
  ) extends DistributedAdmissionLease

  def make(
    config: RedisAdmissionConfig,
    metrics: MetricsRegistry = MetricsRegistry.noop,
  ): ZIO[Scope, DistributedAdmission.Error, RedisDistributedAdmission] =
    for
      _      <- ZIO.fromEither(config.validate).mapError(DistributedAdmission.Error.InvalidRequest.apply)
      env    <- ZLayer
                  .make[Redis](
                    ZLayer.succeed(CodecSupplier.utf8),
                    ZLayer.succeed(config.redisConfig),
                    Redis.singleNode,
                  )
                  .build
                  .mapError(error => DistributedAdmission.Error.Unavailable(error.getClass.getSimpleName))
      redis   = env.get[Redis]
      sha    <- redis
                  .scriptLoad(RedisAdmissionScripts.Coordinator)
                  .mapError(error => DistributedAdmission.Error.Unavailable(error.getClass.getSimpleName))
      shaRef <- Ref.make(sha)
    yield RedisDistributedAdmission(redis, config, shaRef, metrics)

  def layer(
    config: RedisAdmissionConfig
  ): ZLayer[MetricsRegistry, DistributedAdmission.Error, DistributedAdmission & DistributedAdmissionControl] =
    ZLayer.scopedEnvironment {
      for
        metrics <- ZIO.service[MetricsRegistry]
        live    <- make(config, metrics)
      yield ZEnvironment[DistributedAdmission](live).add[DistributedAdmissionControl](live)
    }

  private[redis] def fromRedis(
    redis: Redis,
    config: RedisAdmissionConfig,
    metrics: MetricsRegistry = MetricsRegistry.noop,
  ): IO[DistributedAdmission.Error, RedisDistributedAdmission] =
    for
      _      <- ZIO.fromEither(config.validate).mapError(DistributedAdmission.Error.InvalidRequest.apply)
      sha    <- redis
                  .scriptLoad(RedisAdmissionScripts.Coordinator)
                  .mapError(error => DistributedAdmission.Error.Unavailable(error.getClass.getSimpleName))
      shaRef <- Ref.make(sha)
    yield RedisDistributedAdmission(redis, config, shaRef, metrics)
