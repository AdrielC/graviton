package graviton.integration.shardcake

import com.devsisters.shardcake.interfaces.{Serialization, Storage}
import com.devsisters.shardcake.{LocalSharding, ShardManagerClient, Sharding}
import graviton.core.attributes.IngestStats
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.types.FileSize
import graviton.runtime.upload.*
import graviton.runtime.Graviton
import graviton.runtime.metrics.MetricsRegistry
import zio.*
import zio.blocks.mediatype.MediaTypes
import zio.http.*
import zio.stream.ZStream
import zio.test.*

object ShardcakeIntegrationSpec extends ZIOSpecDefault:
  private val token   = ShardcakeInternalToken.applyUnsafe("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGH")
  private val config  = ShardcakeUploadConfig.Default.copy(
    enabled = true,
    internalToken = Some(token),
  )
  private val tenant  = TenantId.applyUnsafe("9f2f172c-8e6b-4aef-8be8-4c750420d971")
  private val session = UploadSessionId.applyUnsafe("ab573594-abaa-44fa-867a-8c733bf87f6c")
  private val key     = UploadSessionKey(tenant, session)
  private val blobKey = KeyBits
    .fromString(s"sha-256:${"00" * 32}:1")
    .flatMap(BinaryKey.blob)
    .fold(message => throw new IllegalStateException(message), identity)

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("Shardcake integration")(
    test("ZIO Blocks MessagePack round-trips nonzero control values without Kryo") {
      for
        serialization <- ZIO.service[Serialization]
        hot            = UploadHotState.Snapshot(key, UploadHotState.Phase.Active, 17L, 8193L, 100L, 200L)
        encoded       <- serialization.encode(UploadControlReply.Ready(config.node, Some(hot)))
        decoded       <- serialization.decode[UploadControlReply](encoded)
      yield assertTrue(
        decoded == UploadControlReply.Ready(config.node, Some(hot)),
        encoded.length <= 64 * 1024,
      )
    }.provide(ZioBlocksShardcakeSerialization.layer),
    test("control decoding rejects an oversized envelope before schema decoding") {
      for
        serialization <- ZIO.service[Serialization]
        exit          <- serialization.decode[UploadControlReply](new Array[Byte](64 * 1024 + 1)).exit
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[ZioBlocksShardcakeSerialization.Error.EnvelopeTooLarge])
      )
    }.provide(ZioBlocksShardcakeSerialization.layer),
    test("same tenant session resolves to one owner and assignment snapshot is typed") {
      ZIO
        .scoped {
          for
            _           <- UploadControlEntity.register(config.node, 5.minutes)
            _           <- Sharding.registerScoped
            placement   <- ZIO.service[UploadPlacement]
            first       <- placement.locate(key)
            second      <- placement.locate(key)
            assignments <- placement.assignments
            ready       <- ShardcakeNode.verifyAssigned(placement).exit
          yield assertTrue(
            first == config.node,
            second == config.node,
            assignments.nonEmpty,
            assignments
              .forall(assignment => assignment.controlHost == config.node.host && assignment.controlPort == config.node.controlPort),
            ready.isSuccess,
          )
        }
        .provide(
          ZLayer.succeed(config),
          ShardcakeUploadConfig.upstream,
          ZioBlocksShardcakeSerialization.layer,
          Storage.memory,
          ShardManagerClient.local,
          LocalSharding.live,
          UploadHotState.default,
          ShardcakeUploadPlacement.live,
        )
    },
    test("readiness fails when the local upload node owns no shards") {
      val remote    = UploadNode.fromEndpoints(
        UploadNodeHost.applyUnsafe("remote.internal"),
        UploadNodePort.applyUnsafe(8081),
        UploadNodePort.applyUnsafe(8082),
      )
      val placement = new UploadPlacement:
        override val localNode: UIO[UploadNode]                                           = ZIO.succeed(config.node)
        override def locate(key: UploadSessionKey): IO[UploadPlacement.Error, UploadNode] = ZIO.succeed(remote)
        override val assignments: IO[UploadPlacement.Error, Chunk[UploadShardAssignment]] =
          ZIO.succeed(Chunk(UploadShardAssignment(0, remote.host, remote.controlPort)))

      for exit <- ShardcakeNode.verifyAssigned(placement).exit
      yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[ShardcakeNode.HealthError.LocalNodeUnassigned]))
    },
    test("authenticated HTTP node transport streams 16 MiB without materializing the upload") {
      val byteCount = 16L * 1024L * 1024L
      val frame     = Chunk.fill(64 * 1024)(1.toByte)
      for
        ownerRef  <- Ref.make[Option[UploadNode]](None)
        observed  <- Ref.make(0L)
        ingest     = countingIngest(ownerRef, observed)
        port      <- Server.install(UploadNodeHttpApi(token, ingest).routes)
        owner      = UploadNode.fromEndpoints(
                       UploadNodeHost.applyUnsafe("127.0.0.1"),
                       config.node.controlPort,
                       UploadNodePort.applyUnsafe(port),
                     )
        _         <- ownerRef.set(Some(owner))
        transport <- ZIO.service[UploadNodeTransport]
        result    <- transport.upload(
                       owner,
                       key,
                       UploadIntent(MediaTypes.application.`octet-stream`, Some(FileSize.applyUnsafe(byteCount))),
                       ZStream.fromIterable(0 until 256).flatMap(_ => ZStream.fromChunk(frame)),
                     )
        count     <- observed.get
      yield assertTrue(result.owner == owner, result.stats.totalBytes == byteCount, count == byteCount)
    }.provide(
      Server.defaultWith(_.onAnyOpenPort.enableRequestStreaming),
      (Client.default ++ ZLayer.succeed(config)) >>> ZioHttpUploadNodeTransport.live,
    ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds),
    test("internal upload authentication rejects before pulling the request body") {
      for
        pulled    <- Ref.make(false)
        ownerRef  <- Ref.make[Option[UploadNode]](Some(config.node))
        observed  <- Ref.make(0L)
        api        = UploadNodeHttpApi(token, countingIngest(ownerRef, observed))
        handler    = api.routes.toHandler
        url       <- ZIO.fromEither(URL.decode(s"http://localhost/internal/graviton/uploads/${tenant.value}/${session.value}"))
        request    = Request.post(url, Body.fromStreamChunked(ZStream.fromZIO(pulled.set(true)).as(1.toByte)))
        response  <- ZIO.scoped(handler(request))
        wasPulled <- pulled.get
      yield assertTrue(response.status == Status.Unauthorized, !wasPulled)
    },
    test("internal upload validates Content-Length before pulling the request body") {
      for
        pulled    <- Ref.make(false)
        ownerRef  <- Ref.make[Option[UploadNode]](Some(config.node))
        observed  <- Ref.make(0L)
        api        = UploadNodeHttpApi(token, countingIngest(ownerRef, observed))
        handler    = api.routes.toHandler
        url       <- ZIO.fromEither(URL.decode(s"http://localhost/internal/graviton/uploads/${tenant.value}/${session.value}"))
        request    = Request
                       .post(url, Body.fromStreamChunked(ZStream.fromZIO(pulled.set(true)).as(1.toByte)))
                       .addHeader(Header.Custom(ShardcakeInternalAuth.HeaderName, token.value))
                       .addHeader(Header.Custom("Content-Type", "application/octet-stream"))
                       .addHeader(Header.Custom("Content-Length", "+1"))
        response  <- ZIO.scoped(handler(request))
        wasPulled <- pulled.get
      yield assertTrue(response.status == Status.BadRequest, !wasPulled)
    },
    test("owner-local PDF ingest creates reusable CAS blocks and keeps session context scoped") {
      val header      = "%PDF-1.7\n"
      val objectText  = s"1 0 obj\n<</Type /Example /Value (${"x" * 160})>>\nendobj\n"
      val trailer     = "trailer\n<</Root 1 0 R>>\nstartxref\n0\n%%EOF\n"
      val repetitions = 7000
      val totalBytes  = header.length.toLong + objectText.length.toLong * repetitions.toLong + trailer.length.toLong
      val stream      =
        ZStream.fromIterable(header).map(_.toByte) ++
          ZStream.fromIterable(0 until repetitions).flatMap(_ => ZStream.fromIterable(objectText).map(_.toByte)) ++
          ZStream.fromIterable(trailer).map(_.toByte)

      for
        graviton <- Graviton.inMemory(chunkSize = 1024 * 1024)
        ingest   <- ZIO
                      .service[UploadNodeIngest]
                      .provide(
                        ZLayer.succeed(graviton.blobStore),
                        ZLayer.succeed(config),
                        UploadHotState.default,
                        UploadSessionContext.live,
                        ZLayer.succeed[MetricsRegistry](MetricsRegistry.noop),
                        CasUploadNodeIngest.live,
                      )
        result   <- ingest.uploadLocal(
                      key,
                      UploadIntent(zio.pdf.PdfMime.mimeType, Some(FileSize.applyUnsafe(totalBytes))),
                      stream,
                    )
        detail   <- graviton.blobStore.inspect(result.key).someOrFailException
        restored <- graviton.blobStore.get(result.key).runCount
      yield assertTrue(
        restored == totalBytes,
        result.stats.totalBytes == totalBytes,
        detail.blocks.length >= 2,
        detail.blocks.forall(_.size <= 4L * 1024L * 1024L),
      )
    } @@ TestAspect.timeout(30.seconds),
    test("enabled ZIO Config fails closed without an internal token") {
      val provider = ConfigProvider.fromMap(Map("graviton.shardcake.enabled" -> "true"))
      for exit <- ZIO.withConfigProvider(provider)(ZIO.config(ShardcakeUploadConfig.config)).exit
      yield assertTrue(exit.isFailure)
    },
  ) @@ TestAspect.sequential

  private def countingIngest(
    ownerRef: Ref[Option[UploadNode]],
    observed: Ref[Long],
  ): UploadNodeIngest = new UploadNodeIngest:
    override def uploadLocal(
      key: UploadSessionKey,
      intent: UploadIntent,
      bytes: ZStream[Any, Throwable, Byte],
    ): IO[UploadNodeIngest.Error, LocalizedUploadResult] =
      for
        count <- bytes.runCount.mapError(UploadNodeIngest.Error.StorageFailure.apply)
        _     <- observed.set(count)
        owner <- ownerRef.get.someOrFail(UploadNodeIngest.Error.InvalidUpload("test owner not initialized"))
      yield LocalizedUploadResult(blobKey, IngestStats(count, 1, 1, 0, 0.01), owner)
