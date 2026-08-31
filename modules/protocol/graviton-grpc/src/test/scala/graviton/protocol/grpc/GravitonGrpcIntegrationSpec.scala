package graviton.protocol.grpc

import graviton.core.types.FileSize
import graviton.runtime.Graviton
import graviton.runtime.admission.DistributedTrafficQuota
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKeys, MetricsRegistry}
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.{StoreError, StoreOperation}
import graviton.runtime.tenant.{TenantContext, TenantRoute, TenantStoreBinding, TenantStoreProvider}
import graviton.runtime.upload.{TenantId, UploadIngestor, UploadIntent}
import graviton.security.*
import graviton.shared.MediaTypeText
import io.grpc.Status
import zio.*
import zio.blocks.mediatype.MediaType
import zio.stream.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object GravitonGrpcIntegrationSpec extends ZIOSpecDefault:

  private val ContentType = MediaType.unsafeFromString("application/octet-stream; profile=graviton-test")

  override def spec: Spec[TestEnvironment, Any] =
    suite("Graviton gRPC transport")(
      test("serves a real streaming put/get/stat/list/inspect/delete round trip over a socket") {
        ZIO.scoped {
          for
            graviton   <- Graviton.inMemory(chunkSize = 64 * 1024)
            server     <- GravitonGrpcServer.scoped(graviton.blobStore, GrpcServerConfig(port = 0))
            port       <- server.port
            client     <- GravitonGrpcClient.scoped("127.0.0.1", port)
            _          <- client.health
            source      = ZStream.fromIterable(0 until 12 * 1024 * 1024).map(index => (index % 251).toByte)
            written    <- client.put(
                            source,
                            ContentType,
                            Some(FileSize.applyUnsafe(12L * 1024 * 1024)),
                          )
            downloaded <- client.get(written.key).runCollect
            stat       <- client.stat(written.key)
            listed     <- client.list.runCollect
            blocks     <- client.inspect(written.key).runCollect
            _          <- client.delete(written.key)
            missing    <- client.stat(written.key).exit
          yield assertTrue(
            written.size.value == 12L * 1024 * 1024,
            written.contentType == ContentType,
            downloaded.length == 12 * 1024 * 1024,
            downloaded(0) == 0.toByte,
            downloaded(250) == 250.toByte,
            downloaded(251) == 0.toByte,
            stat.size == written.size,
            listed.exists(_.key == written.key),
            blocks.nonEmpty,
            statusCode(missing).contains(Status.Code.NOT_FOUND),
          )
        }
      } @@ TestAspect.timeout(90.seconds),
      test("rejects an oversized upload frame before it reaches storage") {
        val oversized = com.google.protobuf.ByteString.copyFrom(new Array[Byte](GrpcProtocol.MaxChunkBytes + 1))
        for
          graviton <- Graviton.inMemory()
          service   = new BlobServiceImpl(graviton.blobStore)
          exit     <- service
                        .putBlob(
                          ZStream(
                            io.graviton.blobstore.v1.blob_service.PutBlobRequest(
                              io.graviton.blobstore.v1.blob_service.PutBlobRequest.Kind.Metadata(
                                io.graviton.blobstore.v1.blob_service.PutBlobMetadata(
                                  contentType = MediaTypeText.render(ContentType)
                                )
                              )
                            ),
                            io.graviton.blobstore.v1.blob_service.PutBlobRequest(
                              io.graviton.blobstore.v1.blob_service.PutBlobRequest.Kind.Data(oversized)
                            ),
                          )
                        )
                        .exit
        yield assertTrue(statusCode(exit).contains(Status.Code.INVALID_ARGUMENT))
      },
      test("rejects expected-size overflow and underflow before manifest commit") {
        import io.graviton.blobstore.v1.blob_service.*

        def request(expected: Long, bytes: Chunk[Byte]) =
          ZStream(
            PutBlobRequest(
              PutBlobRequest.Kind.Metadata(
                PutBlobMetadata(expectedSize = Some(expected), contentType = MediaTypeText.render(ContentType))
              )
            ),
            PutBlobRequest(PutBlobRequest.Kind.Data(com.google.protobuf.ByteString.copyFrom(bytes.toArray))),
          )

        for
          graviton   <- Graviton.inMemory()
          service     = new BlobServiceImpl(graviton.blobStore)
          overflow   <- service.putBlob(request(3L, Chunk(1, 2, 3, 4).map(_.toByte))).exit
          afterOver  <- graviton.blobStore.streamInventory.runCollect
          underflow  <- service.putBlob(request(5L, Chunk(1, 2, 3, 4).map(_.toByte))).exit
          afterUnder <- graviton.blobStore.streamInventory.runCollect
        yield assertTrue(
          statusCode(overflow).contains(Status.Code.INVALID_ARGUMENT),
          statusCode(underflow).contains(Status.Code.INVALID_ARGUMENT),
          afterOver.isEmpty,
          afterUnder.isEmpty,
        )
      },
      test("maps typed tenant exhaustion without exposing configured limits") {
        import io.graviton.blobstore.v1.blob_service.*

        val failures = Chunk(
          StoreError.TenantStorageQuotaExceeded(
            StoreOperation.PutBlob,
            limitBytes = 987654321L,
            retainedBytes = 987654321L,
            attemptedAdditionalBytes = 1L,
          )                                                                                 -> Status.Code.RESOURCE_EXHAUSTED,
          StoreError.CapacityExceeded(StoreOperation.PutBlob, 987654321L, Some(987654322L)) -> Status.Code.RESOURCE_EXHAUSTED,
          StoreError.TenantConcurrencyExceeded(StoreOperation.PutBlob)                      -> Status.Code.RESOURCE_EXHAUSTED,
          StoreError.TenantAdmissionUnavailable(StoreOperation.PutBlob)                     -> Status.Code.UNAVAILABLE,
        )

        def failingIngestor(error: StoreError) = new UploadIngestor:
          override def put(intent: UploadIntent, bytes: ZStream[Any, Throwable, Byte], plan: BlobWritePlan) =
            ZIO.fail(UploadIngestor.Error.Storage(error))

        for
          graviton <- Graviton.inMemory()
          denied   <- ZIO.foreach(failures) { case (storeError, expectedCode) =>
                        new BlobServiceImpl(graviton.blobStore, failingIngestor(storeError))
                          .putBlob(
                            ZStream(
                              PutBlobRequest(
                                PutBlobRequest.Kind.Metadata(PutBlobMetadata(contentType = MediaTypeText.render(ContentType)))
                              ),
                              PutBlobRequest(PutBlobRequest.Kind.Data(com.google.protobuf.ByteString.copyFrom(Array[Byte](1)))),
                            )
                          )
                          .exit
                          .map(exit => exit -> expectedCode)
                      }
        yield assertTrue(
          denied.forall { case (exit, expectedCode) => statusCode(exit).contains(expectedCode) },
          denied.forall { case (exit, _) =>
            !exit.causeOption
              .flatMap(_.failureOption)
              .flatMap(error => Option(error.getStatus.getDescription))
              .exists(_.contains("987654321"))
          },
        )
      },
      test("carries a logical 1 TiB expected size over gRPC without allocation and rejects immediate EOF atomically") {
        ZIO.scoped {
          val oneTiB = 1024L * 1024L * 1024L * 1024L

          for
            logicalSize <- ZIO
                             .fromEither(FileSize.either(oneTiB))
                             .orDieWith(message => new IllegalArgumentException(message))
            graviton    <- Graviton.inMemory()
            server      <- GravitonGrpcServer.scoped(graviton.blobStore, GrpcServerConfig(port = 0))
            port        <- server.port
            client      <- GravitonGrpcClient.scoped("127.0.0.1", port)
            underflow   <- client.put(ZStream.empty, ContentType, Some(logicalSize)).exit
            listed      <- graviton.blobStore.streamInventory.runCollect
            failure      = underflow.causeOption.flatMap(_.failureOption)
          yield assertTrue(
            logicalSize.value == oneTiB,
            statusCode(underflow).contains(Status.Code.INVALID_ARGUMENT),
            failure.exists(_.getStatus.getDescription == s"expected $oneTiB bytes but received 0"),
            listed.isEmpty,
          )
        }
      } @@ TestAspect.timeout(20.seconds),
      test("enforces bearer authentication and blob capabilities on the real listener") {
        ZIO.scoped {
          for
            graviton  <- Graviton.inMemory()
            audit     <- AuditSink.inMemory
            runtime   <- ZIO.runtime[Any]
            readOnly   = caller(CapabilitySet.of(Capability.BlobRead))
            allowAll   = new RateLimiter:
                           def check(kind: RateLimiter.Kind, tokens: Long): IO[SecurityError, Unit] = ZIO.unit
            server    <- GravitonGrpcServer.scoped(
                           graviton.blobStore,
                           GrpcServerConfig(port = 0),
                           List(
                             new AuthInterceptor(JwtVerifier.static(readOnly), audit, runtime),
                             new CapabilityInterceptor(CapabilityCheck.tokenOnly, runtime, Some(audit)),
                             new RateLimitInterceptor(allowAll, runtime),
                           ),
                         )
            port      <- server.port
            anonymous <- GravitonGrpcClient.scoped("127.0.0.1", port)
            secured   <- GravitonGrpcClient.scoped(
                           "127.0.0.1",
                           port,
                           Some(GravitonGrpcClient.BearerToken.applyUnsafe("integration-token")),
                         )
            _         <- anonymous.health
            unauth    <- anonymous.put(ZStream.succeed(1.toByte), ContentType).exit
            forbidden <- secured.put(ZStream.succeed(1.toByte), ContentType).exit
            records   <- audit.drain
          yield assertTrue(
            statusCode(unauth).contains(Status.Code.UNAUTHENTICATED),
            statusCode(forbidden).contains(Status.Code.PERMISSION_DENIED),
            records.exists(record => record.action.endsWith("/PutBlob") && record.outcome == AuditEvent.Outcome.Deny),
          )
        }
      },
      test("executes an authorized lifecycle through the complete interceptor chain") {
        ZIO.scoped {
          for
            graviton  <- Graviton.inMemory()
            audit     <- AuditSink.inMemory
            runtime   <- ZIO.runtime[Any]
            charges   <- Ref.make(Chunk.empty[(RateLimiter.Kind, Long)])
            caller0    = caller(CapabilitySet.of(Capability.BlobRead, Capability.BlobWrite, Capability.BlobDelete))
            allowAll   = new RateLimiter:
                           def check(kind: RateLimiter.Kind, tokens: Long): IO[SecurityError, Unit] =
                             charges.update(_ :+ (kind -> tokens))
            server    <- GravitonGrpcServer.scoped(
                           graviton.blobStore,
                           GrpcServerConfig(port = 0),
                           List(
                             new AuthInterceptor(JwtVerifier.static(caller0), audit, runtime),
                             new CapabilityInterceptor(CapabilityCheck.tokenOnly, runtime, Some(audit)),
                             new RateLimitInterceptor(allowAll, runtime),
                           ),
                         )
            port      <- server.port
            client    <- GravitonGrpcClient.scoped(
                           "127.0.0.1",
                           port,
                           Some(GravitonGrpcClient.BearerToken.applyUnsafe("integration-token")),
                         )
            _         <- client.health
            source     = ZStream.range(0, 2 * 1024 * 1024).map(index => (index % 251).toByte)
            written   <- client.put(source, ContentType)
            received  <- client.get(written.key).runCount
            _         <- client.delete(written.key)
            missing   <- client.stat(written.key).exit
            records   <- audit.drain
            charged   <- charges.get
            uploaded   = charged.collect { case (RateLimiter.Kind.UploadBytes, bytes) => bytes }.sum
            downloaded = charged.collect { case (RateLimiter.Kind.DownloadBytes, bytes) => bytes }.sum
          yield assertTrue(
            received == 2L * 1024 * 1024,
            uploaded == 2L * 1024 * 1024,
            downloaded == 2L * 1024 * 1024,
            statusCode(missing).contains(Status.Code.NOT_FOUND),
            records.exists(record => record.action.endsWith("/PutBlob") && record.outcome == AuditEvent.Outcome.Allow),
            records.exists(record => record.action.endsWith("/DeleteBlob") && record.outcome == AuditEvent.Outcome.Allow),
          )
        }
      } @@ TestAspect.timeout(20.seconds),
      test("routes authenticated organizations to isolated stores on the real listener") {
        ZIO.scoped {
          for
            first            <- Graviton.inMemory(chunkSize = 64)
            second           <- Graviton.inMemory(chunkSize = 64)
            tenantContextEnv <- TenantContext.live.build
            tenantContext     = tenantContextEnv.get[TenantContext]
            firstCaller       = callerFor("10000000-0000-4000-8000-000000000001")
            secondCaller      = callerFor("20000000-0000-4000-8000-000000000002")
            firstTenant       = TenantId.applyUnsafe(firstCaller.orgId.toString)
            secondTenant      = TenantId.applyUnsafe(secondCaller.orgId.toString)
            provider         <- ZIO.fromEither(
                                  TenantStoreProvider.static(
                                    Chunk(
                                      TenantStoreBinding(TenantRoute(firstTenant), first.blobStore),
                                      TenantStoreBinding(TenantRoute(secondTenant), second.blobStore),
                                    )
                                  )
                                )
            audit            <- AuditSink.inMemory
            runtime          <- ZIO.runtime[Any]
            allowAll          = new RateLimiter:
                                  def check(kind: RateLimiter.Kind, tokens: Long): IO[SecurityError, Unit] = ZIO.unit
            verifier          = tokenVerifier(Map("tenant-a" -> firstCaller, "tenant-b" -> secondCaller))
            server           <- GravitonGrpcServer.scopedTenants(
                                  first.blobStore,
                                  provider,
                                  tenantContext,
                                  UploadIngestor.default,
                                  GrpcServerConfig(port = 0),
                                  List(
                                    new AuthInterceptor(verifier, audit, runtime),
                                    new CapabilityInterceptor(CapabilityCheck.tokenOnly, runtime, Some(audit)),
                                    new RateLimitInterceptor(allowAll, runtime),
                                  ),
                                )
            port             <- server.port
            firstClient      <- GravitonGrpcClient.scoped(
                                  "127.0.0.1",
                                  port,
                                  Some(GravitonGrpcClient.BearerToken.applyUnsafe("tenant-a")),
                                )
            secondClient     <- GravitonGrpcClient.scoped(
                                  "127.0.0.1",
                                  port,
                                  Some(GravitonGrpcClient.BearerToken.applyUnsafe("tenant-b")),
                                )
            bytes             = Chunk.fromArray("grpc-organization-private".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            firstWrite       <- firstClient.put(ZStream.fromChunk(bytes), ContentType)
            firstRead        <- firstClient.get(firstWrite.key).runCollect
            hiddenFromSecond <- secondClient.stat(firstWrite.key).exit
            secondWrite      <- secondClient.put(ZStream.fromChunk(bytes), ContentType)
            callerAfterCalls <- CallerContext.current
          yield assertTrue(
            firstRead == bytes,
            statusCode(hiddenFromSecond).contains(Status.Code.NOT_FOUND),
            secondWrite.key == firstWrite.key,
            callerAfterCalls.isEmpty,
          )
        }
      } @@ TestAspect.timeout(20.seconds),
      test("charges distributed request and delivered-egress quotas on authenticated gRPC") {
        ZIO.scoped {
          for
            graviton <- Graviton.inMemory(chunkSize = 64 * 1024)
            audit    <- AuditSink.inMemory
            metrics  <- InMemoryMetricsRegistry.make
            runtime  <- ZIO.runtime[Any]
            charges  <- Ref.make(Chunk.empty[(TenantId, DistributedTrafficQuota.Kind, Long)])
            caller0   = callerFor("10000000-0000-4000-8000-000000000001")
            quota     = new DistributedTrafficQuota:
                          override def charge(
                            tenantId: TenantId,
                            kind: DistributedTrafficQuota.Kind,
                            amount: Long,
                          ): IO[DistributedTrafficQuota.Error, Unit] =
                            charges.update(_ :+ ((tenantId, kind, amount)))
            allowAll  = new RateLimiter:
                          def check(kind: RateLimiter.Kind, tokens: Long): IO[SecurityError, Unit] = ZIO.unit
            server   <- GravitonGrpcServer.scoped(
                          graviton.blobStore,
                          GrpcServerConfig(port = 0),
                          List(
                            new AuthInterceptor(JwtVerifier.static(caller0), audit, runtime),
                            new CapabilityInterceptor(CapabilityCheck.tokenOnly, runtime, Some(audit)),
                            new RateLimitInterceptor(allowAll, runtime),
                          ),
                          Some(TrafficQuotaBlobService.Dependencies(quota, metrics)),
                        )
            port     <- server.port
            client   <- GravitonGrpcClient.scoped(
                          "127.0.0.1",
                          port,
                          Some(GravitonGrpcClient.BearerToken.applyUnsafe("integration-token")),
                        )
            bytes     = Chunk.fill(2 * 1024 * 1024)(0x5a.toByte)
            written  <- client.put(ZStream.fromChunk(bytes), ContentType)
            received <- client.get(written.key).runCount
            recorded <- charges.get
            snapshot <- metrics.snapshot
            tenant    = TenantId.applyUnsafe(caller0.orgId.toString)
            requests  = recorded.collect { case (`tenant`, DistributedTrafficQuota.Kind.Request, amount) => amount }.sum
            egress    = recorded.collect { case (`tenant`, DistributedTrafficQuota.Kind.DeliveredEgress, amount) => amount }.sum
            metric    = snapshot.counters.collectFirst {
                          case (key, value) if key.name == MetricKeys.DeliveredEgressBytesTotal && key.tags == Map("protocol" -> "grpc") =>
                            value
                        }
          yield assertTrue(
            received == bytes.length.toLong,
            requests == 2L,
            egress == bytes.length.toLong,
            metric.contains(bytes.length.toLong),
          )
        }
      } @@ TestAspect.timeout(20.seconds),
      test("terminates gRPC download before sending a quota-rejected frame") {
        ZIO.scoped {
          for
            graviton <- Graviton.inMemory(chunkSize = 64 * 1024)
            seed      = ZStream.fromChunk(Chunk.fill(256 * 1024)(0x33.toByte))
            stored   <- seed.run(graviton.blobStore.put())
            audit    <- AuditSink.inMemory
            runtime  <- ZIO.runtime[Any]
            caller0   = callerFor("10000000-0000-4000-8000-000000000001")
            quota     = new DistributedTrafficQuota:
                          override def charge(
                            tenantId: TenantId,
                            kind: DistributedTrafficQuota.Kind,
                            amount: Long,
                          ): IO[DistributedTrafficQuota.Error, Unit] =
                            val _ = (tenantId, amount)
                            kind match
                              case DistributedTrafficQuota.Kind.Request         => ZIO.unit
                              case DistributedTrafficQuota.Kind.DeliveredEgress =>
                                ZIO.fail(DistributedTrafficQuota.Error.Rejected(kind, 1L, 1.second))
            allowAll  = new RateLimiter:
                          def check(kind: RateLimiter.Kind, tokens: Long): IO[SecurityError, Unit] = ZIO.unit
            server   <- GravitonGrpcServer.scoped(
                          graviton.blobStore,
                          GrpcServerConfig(port = 0),
                          List(
                            new AuthInterceptor(JwtVerifier.static(caller0), audit, runtime),
                            new CapabilityInterceptor(CapabilityCheck.tokenOnly, runtime, Some(audit)),
                            new RateLimitInterceptor(allowAll, runtime),
                          ),
                          Some(TrafficQuotaBlobService.Dependencies(quota, MetricsRegistry.noop)),
                        )
            port     <- server.port
            client   <- GravitonGrpcClient.scoped(
                          "127.0.0.1",
                          port,
                          Some(GravitonGrpcClient.BearerToken.applyUnsafe("integration-token")),
                        )
            denied   <- client.get(stored.key).runCollect.exit
          yield assertTrue(statusCode(denied).contains(Status.Code.RESOURCE_EXHAUSTED))
        }
      } @@ TestAspect.timeout(20.seconds),
      test("stops a rate-limited upload frame before it reaches storage") {
        ZIO.scoped {
          for
            graviton <- Graviton.inMemory()
            audit    <- AuditSink.inMemory
            runtime  <- ZIO.runtime[Any]
            caller0   = caller(CapabilitySet.of(Capability.BlobWrite))
            limiter   = new RateLimiter:
                          def check(kind: RateLimiter.Kind, tokens: Long): IO[SecurityError, Unit] =
                            kind match
                              case RateLimiter.Kind.UploadBytes =>
                                ZIO.fail(SecurityError.RateLimited("test upload budget exhausted"))
                              case _                            => ZIO.unit
            server   <- GravitonGrpcServer.scoped(
                          graviton.blobStore,
                          GrpcServerConfig(port = 0),
                          List(
                            new AuthInterceptor(JwtVerifier.static(caller0), audit, runtime),
                            new CapabilityInterceptor(CapabilityCheck.tokenOnly, runtime, Some(audit)),
                            new RateLimitInterceptor(limiter, runtime),
                          ),
                        )
            port     <- server.port
            client   <- GravitonGrpcClient.scoped(
                          "127.0.0.1",
                          port,
                          Some(GravitonGrpcClient.BearerToken.applyUnsafe("integration-token")),
                        )
            denied   <- client.put(ZStream.fromChunk(Chunk.fill(64 * 1024)(1.toByte)), ContentType).exit
            listed   <- graviton.blobStore.streamInventory.runCollect
          yield assertTrue(
            statusCode(denied).contains(Status.Code.RESOURCE_EXHAUSTED),
            listed.isEmpty,
          )
        }
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(20.seconds),
    )

  private def statusCode[A](exit: Exit[io.grpc.StatusException, A]): Option[Status.Code] =
    exit.causeOption.flatMap(_.failureOption).map(_.getStatus.getCode)

  private def caller(capabilities: CapabilitySet): CallerContext =
    CallerContext(
      orgId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
      principalId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
      capabilities = capabilities,
      jti = "grpc-integration",
      tokenExpiresAt = Instant.parse("2099-01-01T00:00:00Z"),
      requestId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
    )

  private def callerFor(orgId: String): CallerContext =
    CallerContext(
      orgId = UUID.fromString(orgId),
      principalId = UUID.randomUUID(),
      capabilities = CapabilitySet.of(Capability.BlobRead, Capability.BlobWrite, Capability.BlobDelete),
      jti = UUID.randomUUID().toString,
      tokenExpiresAt = Instant.parse("2099-01-01T00:00:00Z"),
      requestId = UUID.randomUUID(),
    )

  private def tokenVerifier(contexts: Map[String, CallerContext]): JwtVerifier =
    new JwtVerifier:
      override def verify(bearerToken: String, requestId: UUID): IO[SecurityError, CallerContext] =
        ZIO
          .fromOption(contexts.get(bearerToken))
          .orElseFail(SecurityError.Unauthenticated("unknown test token"))
          .map(_.copy(requestId = requestId))
