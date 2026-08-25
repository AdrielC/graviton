package graviton.protocol.grpc

import graviton.runtime.Graviton
import graviton.security.*
import io.grpc.Status
import zio.*
import zio.blocks.mediatype.MediaType
import zio.stream.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object GravitonGrpcIntegrationSpec extends ZIOSpecDefault:

  private val ContentType = MediaType.unsafeFromString("application/octet-stream")

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
            written    <- client.put(source, ContentType)
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
                                io.graviton.blobstore.v1.blob_service.PutBlobMetadata(contentType = ContentType.fullType)
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
            denied   <- client.put(ZStream.range(0, 2 * 1024 * 1024).map(_.toByte), ContentType).exit
            listed   <- graviton.blobStore.list
          yield assertTrue(
            statusCode(denied).contains(Status.Code.RESOURCE_EXHAUSTED),
            listed.isEmpty,
          )
        }
      } @@ TestAspect.timeout(20.seconds),
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
