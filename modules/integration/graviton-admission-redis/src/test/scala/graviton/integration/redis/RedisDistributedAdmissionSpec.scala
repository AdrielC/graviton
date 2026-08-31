package graviton.integration.redis

import graviton.runtime.admission.*
import graviton.runtime.stores.*
import graviton.runtime.tenant.TenantCellId
import graviton.runtime.upload.TenantId
import zio.*
import zio.redis.{CodecSupplier, Input, Output, Redis, RedisError}
import zio.test.*

import java.util.UUID

object RedisDistributedAdmissionSpec extends ZIOSpecDefault:

  private val tenantA   = TenantId.applyUnsafe("11111111-1111-4111-8111-111111111111")
  private val tenantB   = TenantId.applyUnsafe("22222222-2222-4222-8222-222222222222")
  private val component = TransferComponent.applyUnsafe("redis-admission-test")

  private def request(tenant: TenantId, bytes: Long = 512L): DistributedAdmissionRequest =
    DistributedAdmissionRequest(
      TransferScope(Some(tenant), StoreBackend.S3),
      StoreOperation.PutBlob,
      TransferFootprint.single(component, bytes).toOption.get,
    )

  private def config(
    tenantConcurrency: Int = 1,
    serviceConcurrency: Int = 2,
  ): RedisAdmissionConfig =
    RedisAdmissionConfig.Default.copy(
      enabled = true,
      cellId = TenantCellId.applyUnsafe(s"it-${UUID.randomUUID().toString.take(12)}"),
      port = sys.env.get("GRAVITON_REDIS_IT_PORT").flatMap(_.toIntOption).getOrElse(6379),
      limits = DistributedAdmissionLimits(
        maximumServiceBufferedBytes = DistributedBufferedBytes.applyUnsafe(2048L),
        maximumConcurrentServiceTransfers = DistributedTransferConcurrency.applyUnsafe(serviceConcurrency),
        maximumTenantBufferedBytes = DistributedBufferedBytes.applyUnsafe(1024L),
        maximumConcurrentTenantTransfers = DistributedTransferConcurrency.applyUnsafe(tenantConcurrency),
        maximumConcurrentBackendTransfers = DistributedTransferConcurrency.applyUnsafe(serviceConcurrency),
      ),
      leaseTtl = 3.seconds,
      renewalInterval = 500.millis,
      acquisitionTimeout = 300.millis,
      retryInterval = 20.millis,
      maximumEvents = 1000L,
    )

  private def hold(
    admission: DistributedAdmission,
    request: DistributedAdmissionRequest,
    entered: Promise[Nothing, Unit],
    release: Promise[Nothing, Unit],
  ): ZIO[Any, DistributedAdmission.Error, Unit] =
    ZIO.scoped(admission.acquireScoped(request) *> entered.succeed(()) *> release.await)

  private def rawRedis(config: RedisAdmissionConfig): ZIO[Scope, DistributedAdmission.Error, Redis] =
    ZLayer
      .make[Redis](
        ZLayer.succeed(CodecSupplier.utf8),
        ZLayer.succeed(config.redisConfig),
        Redis.singleNode,
      )
      .build
      .map(_.get[Redis])
      .mapError(error => DistributedAdmission.Error.Unavailable(error.getClass.getSimpleName))

  private def rawExecute(
    redis: Redis,
    config: RedisAdmissionConfig,
    action: String,
    arguments: Chunk[String],
  ): IO[RedisError, String] =
    for
      sha    <- redis.scriptLoad(RedisAdmissionScripts.Coordinator)
      result <- redis
                  .evalSha[String, String](
                    sha,
                    RedisDistributedAdmission.keys(config),
                    Chunk(action, config.maximumEvents.toString, config.maximumExpiredLeasesPerPass.toString) ++ arguments,
                  )(using Input.StringInput, Input.StringInput)
                  .returning[String](using Output.MultiStringOutput)
    yield result

  override def spec: Spec[TestEnvironment & Scope, Any] =
    if !sys.env.get("GRAVITON_REDIS_IT").contains("1") then
      suite("RedisDistributedAdmission integration")(test("is opt-in")(assertTrue(true)))
    else
      suite("RedisDistributedAdmission integration")(
        test("enforces one tenant limit atomically across independent providers") {
          Live.live {
            ZIO.scoped {
              for
                cfg      <- ZIO.succeed(config())
                first    <- RedisDistributedAdmission.make(cfg)
                second   <- RedisDistributedAdmission.make(cfg)
                entered  <- Promise.make[Nothing, Unit]
                release  <- Promise.make[Nothing, Unit]
                holder   <- hold(first, request(tenantA), entered, release).forkScoped
                _        <- entered.await
                rejected <- ZIO.scoped(second.acquireScoped(request(tenantA))).exit
                active   <- first.snapshot(request(tenantA).scope)
                _        <- release.succeed(())
                _        <- holder.join
                accepted <- ZIO.scoped(second.acquireScoped(request(tenantA))).exit
                idle     <- first.snapshot(request(tenantA).scope)
              yield assertTrue(
                rejected.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[DistributedAdmission.Error.TimedOut]),
                active.occupancy.serviceTransfers == 1L,
                active.occupancy.tenantTransfers.contains(1L),
                accepted.isSuccess,
                idle.occupancy.serviceTransfers == 0L,
                idle.occupancy.tenantTransfers.contains(0L),
              )
            }
          }
        },
        test("keeps tenants independent under the shared service ceiling") {
          Live.live {
            ZIO.scoped {
              for
                cfg      <- ZIO.succeed(config())
                first    <- RedisDistributedAdmission.make(cfg)
                second   <- RedisDistributedAdmission.make(cfg)
                enteredA <- Promise.make[Nothing, Unit]
                enteredB <- Promise.make[Nothing, Unit]
                release  <- Promise.make[Nothing, Unit]
                fiberA   <- hold(first, request(tenantA), enteredA, release).forkScoped
                fiberB   <- hold(second, request(tenantB), enteredB, release).forkScoped
                _        <- enteredA.await.zipPar(enteredB.await)
                snapshot <- first.snapshot(request(tenantA).scope)
                _        <- release.succeed(())
                _        <- fiberA.join.zipPar(fiberB.join)
              yield assertTrue(
                snapshot.occupancy.serviceTransfers == 2L,
                snapshot.occupancy.tenantTransfers.contains(1L),
              )
            }
          }
        },
        test("applies a live tenant override without restarting providers") {
          Live.live {
            ZIO.scoped {
              for
                cfg        <- ZIO.succeed(config(tenantConcurrency = 2))
                first      <- RedisDistributedAdmission.make(cfg)
                second     <- RedisDistributedAdmission.make(cfg)
                versionOne <- first.setTenantOverride(
                                tenantA,
                                TenantAdmissionOverride(
                                  maximumConcurrentTransfers = Some(DistributedTransferConcurrency.applyUnsafe(1))
                                ),
                              )
                entered    <- Promise.make[Nothing, Unit]
                release    <- Promise.make[Nothing, Unit]
                holder     <- hold(first, request(tenantA), entered, release).forkScoped
                _          <- entered.await
                rejected   <- ZIO.scoped(second.acquireScoped(request(tenantA))).exit
                versionTwo <- first.clearTenantOverride(tenantA)
                accepted   <- ZIO.scoped(second.acquireScoped(request(tenantA))).exit
                _          <- release.succeed(())
                _          <- holder.join
              yield assertTrue(
                versionOne.value >= 1L,
                versionTwo.value > versionOne.value,
                rejected.isFailure,
                accepted.isSuccess,
              )
            }
          }
        },
        test("enforces request and delivered-egress contracts atomically across providers") {
          Live.live {
            ZIO.scoped {
              for
                cfg       <- ZIO.succeed(
                               config().copy(
                                 maximumTenantRequestsPerMinute = 2L,
                                 maximumTenantDeliveredEgressBytesPerHour = 10L,
                               )
                             )
                first     <- RedisDistributedAdmission.make(cfg)
                second    <- RedisDistributedAdmission.make(cfg)
                _         <- first.charge(tenantA, DistributedTrafficQuota.Kind.Request, 1L)
                _         <- second.charge(tenantA, DistributedTrafficQuota.Kind.Request, 1L)
                requestNo <- first.charge(tenantA, DistributedTrafficQuota.Kind.Request, 1L).exit
                other     <- second.charge(tenantB, DistributedTrafficQuota.Kind.Request, 1L).exit
                _         <- first.charge(tenantA, DistributedTrafficQuota.Kind.DeliveredEgress, 10L)
                egressNo  <- second.charge(tenantA, DistributedTrafficQuota.Kind.DeliveredEgress, 1L).exit
              yield assertTrue(
                requestNo.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[DistributedTrafficQuota.Error.Rejected]),
                other.isSuccess,
                egressNo.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[DistributedTrafficQuota.Error.Rejected]),
              )
            }
          }
        },
        test("renews a live lease beyond its original TTL and releases on interruption") {
          Live.live {
            ZIO.scoped {
              for
                cfg      <- ZIO.succeed(config())
                first    <- RedisDistributedAdmission.make(cfg)
                entered  <- Promise.make[Nothing, Unit]
                release  <- Promise.make[Nothing, Unit]
                holder   <- hold(first, request(tenantA), entered, release).forkScoped
                _        <- entered.await
                _        <- ZIO.sleep(4.seconds)
                renewed  <- first.snapshot(request(tenantA).scope)
                _        <- holder.interrupt
                released <- first.snapshot(request(tenantA).scope).repeatUntil(_.occupancy.serviceTransfers == 0L)
                events   <- first.eventCount
              yield assertTrue(
                renewed.occupancy.serviceTransfers == 1L,
                released.occupancy.serviceTransfers == 0L,
                events >= 2L,
              )
            }
          }
        },
        test("reaps an abandoned lease with server time and restores every counter") {
          Live.live {
            ZIO.scoped {
              for
                cfg      <- ZIO.succeed(config())
                redis    <- rawRedis(cfg)
                admitted <- rawExecute(
                              redis,
                              cfg,
                              "acquire",
                              Chunk(
                                "abandoned-lease",
                                "tenant-hash",
                                "backend-hash",
                                "512",
                                "2048",
                                "2",
                                "1024",
                                "1",
                                "2",
                                cfg.leaseTtl.toMillis.toString,
                                cfg.retryInterval.toMillis.toString,
                                StoreOperation.PutBlob.toString,
                              ),
                            ).mapError(error => DistributedAdmission.Error.Unavailable(error.getClass.getSimpleName))
                _        <- ZIO.sleep(cfg.leaseTtl + 250.millis)
                snapshot <- rawExecute(redis, cfg, "snapshot", Chunk("tenant-hash", "backend-hash"))
                              .mapError(error => DistributedAdmission.Error.Unavailable(error.getClass.getSimpleName))
                events   <- redis
                              .xLen[String](RedisDistributedAdmission.keys(cfg)(5))
                              .mapError(error => DistributedAdmission.Error.Unavailable(error.getClass.getSimpleName))
              yield assertTrue(
                admitted.startsWith("ADMITTED|"),
                snapshot.startsWith("SNAPSHOT|0|0|0|0|0|"),
                events >= 2L,
              )
            }
          }
        },
        test("surfaces fencing loss through the scoped lease") {
          Live.live {
            ZIO.scoped {
              for
                cfg       <- ZIO.succeed(config())
                admission <- RedisDistributedAdmission.make(cfg)
                redis     <- rawRedis(cfg)
                lease     <- admission.acquireScoped(request(tenantA))
                released  <- rawExecute(
                               redis,
                               cfg,
                               "release",
                               Chunk(lease.id.value, lease.fencingToken.value.toString, "externally_fenced"),
                             ).mapError(error => DistributedAdmission.Error.Unavailable(error.getClass.getSimpleName))
                lost      <- lease.revoked.exit.timeout(2.seconds)
              yield assertTrue(
                released == "RELEASED",
                lost.exists(_.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[DistributedAdmission.Error.LeaseLost])),
              )
            }
          }
        },
      ) @@ TestAspect.sequential
