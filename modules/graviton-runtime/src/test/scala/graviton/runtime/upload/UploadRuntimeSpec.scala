package graviton.runtime.upload

import graviton.core.attributes.IngestStats
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.types.FileSize
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKey, MetricKeys, MetricsRegistry}
import zio.*
import zio.blocks.mediatype.MediaTypes
import zio.stream.ZStream
import zio.test.*

object UploadRuntimeSpec extends ZIOSpecDefault:
  private val tenant  = TenantId.applyUnsafe("9f2f172c-8e6b-4aef-8be8-4c750420d971")
  private val session = UploadSessionId.applyUnsafe("ab573594-abaa-44fa-867a-8c733bf87f6c")
  private val key     = UploadSessionKey(tenant, session)
  private val local   = UploadNode.fromEndpoints(
    UploadNodeHost.applyUnsafe("node-a"),
    UploadNodePort.applyUnsafe(54321),
    UploadNodePort.applyUnsafe(54322),
  )
  private val remote  = UploadNode.fromEndpoints(
    UploadNodeHost.applyUnsafe("node-b"),
    UploadNodePort.applyUnsafe(54331),
    UploadNodePort.applyUnsafe(54332),
  )
  private val blobKey = KeyBits
    .parse(s"sha-256:${"00" * 32}:4")
    .flatMap(BinaryKey.blob)
    .fold(message => throw new IllegalStateException(message), identity)
  private val result  = LocalizedUploadResult(blobKey, IngestStats(4L, 1, 1, 0, 0.01), local)
  private val intent  = UploadIntent(MediaTypes.application.`octet-stream`, Some(FileSize.applyUnsafe(4L)))

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("upload runtime")(
    test("typed identifiers reject non-canonical and cross-tenant entity IDs") {
      val invalidTenant = TenantId.either("tenant-1")
      val invalidCase   = UploadSessionId.either("AB573594-ABAA-44FA-867A-8C733BF87F6C")
      val parsed        = UploadSessionKey.parseEntityId(key.entityId)
      val swapped       = UploadSessionKey.parseEntityId(s"${session.value}:${tenant.value}")
      assertTrue(invalidTenant.isLeft, invalidCase.isLeft, parsed.contains(key), swapped.exists(_ != key))
    },
    test("FiberRef session scope inherits on fork and never leaks through join, failure, or interruption") {
      for
        context        <- ZIO.service[UploadSessionContext]
        outsideBefore  <- context.current.exit
        childSeen      <- context.locally(key)(context.current.fork.flatMap(_.join))
        nestedRestored <- context.locally(key) {
                            val other = key.copy(uploadSessionId = UploadSessionId.applyUnsafe("2ee13a0d-9c30-40b4-b3ff-5f5453811029"))
                            context.locally(other)(context.current) *> context.current
                          }
        failed         <- context.locally(key)(ZIO.fail("boom")).exit
        interrupted    <- context.locally(key)(ZIO.never).fork.flatMap(_.interrupt)
        outsideAfter   <- context.current.exit
      yield assertTrue(
        outsideBefore.isFailure,
        childSeen == key,
        nestedRestored == key,
        failed.isFailure,
        interrupted.isFailure,
        outsideAfter.isFailure,
      )
    }.provide(UploadSessionContext.live),
    test("expected size is checked incrementally without pulling past overflow") {
      for
        pulled <- Ref.make(0)
        stream  = ZStream.fromIterable(1 to 5).mapZIO(value => pulled.update(_ + 1).as(value.toByte))
        exit   <- UploadByteStream.enforceExpectedSize(stream, Some(FileSize.applyUnsafe(3L))).runDrain.exit
        count  <- pulled.get
      yield assertTrue(exit.isFailure, count == 4)
    },
    test("hot state is bounded and only observes refined frames") {
      for
        state <- UploadHotState.inMemory(UploadHotState.Config(1))
        frame <- ZIO.fromEither(UploadTransportFrame.fromChunk(Chunk(1.toByte, 2.toByte, 3.toByte)))
        _     <- state.begin(key)
        _     <- state.observe(key, frame)
        first <- state.snapshot(key)
        other  = key.copy(uploadSessionId = UploadSessionId.applyUnsafe("2ee13a0d-9c30-40b4-b3ff-5f5453811029"))
        _     <- state.begin(other)
        size  <- state.size
      yield assertTrue(first.exists(snapshot => snapshot.framesSeen == 1L && snapshot.bytesSeen == 3L), size == 1)
    },
    test("locality routing selects one owner and pulls a live body exactly once") {
      for
        pulls       <- Ref.make(0)
        localCalls  <- Ref.make(0)
        remoteCalls <- Ref.make(0)
        placement    = placementReturning(remote)
        ingest       = ingestCounting(localCalls)
        transport    = transportCounting(remoteCalls)
        service     <- ZIO
                         .service[LocalityAwareUpload]
                         .provide(
                           ZLayer.succeed(placement),
                           ZLayer.succeed(ingest),
                           ZLayer.succeed(transport),
                           LocalityAwareUpload.live,
                         )
        bytes        = ZStream.fromIterable(1 to 4).mapZIO(value => pulls.update(_ + 1).as(value.toByte))
        routed      <- service.upload(key, intent, bytes)
        pullCount   <- pulls.get
        localCount  <- localCalls.get
        remoteCount <- remoteCalls.get
      yield assertTrue(routed.owner == remote, pullCount == 4, localCount == 0, remoteCount == 1)
    },
    test("interrupting locality routing releases the caller-owned source") {
      for
        started      <- Promise.make[Nothing, Unit]
        released     <- Promise.make[Nothing, Unit]
        localCalls   <- Ref.make(0)
        remoteCalls  <- Ref.make(0)
        service      <- ZIO
                          .service[LocalityAwareUpload]
                          .provide(
                            ZLayer.succeed(placementReturning(remote)),
                            ZLayer.succeed(ingestCounting(localCalls)),
                            ZLayer.succeed(transportCounting(remoteCalls)),
                            LocalityAwareUpload.live,
                          )
        bytes         = (ZStream.fromZIO(started.succeed(()).as(1.toByte)) ++ ZStream.never)
                          .ensuring(released.succeed(()).unit)
        fiber        <- service.upload(key, intent.copy(expectedSize = None), bytes).fork
        _            <- started.await
        interrupted  <- fiber.interrupt
        sourceClosed <- released.await.as(true)
        calls        <- remoteCalls.get
      yield assertTrue(interrupted.isFailure, sourceClosed, calls == 1)
    },
    test("locality routing emits bounded-cardinality route metrics") {
      for
        localCalls  <- Ref.make(0)
        remoteCalls <- Ref.make(0)
        registry    <- InMemoryMetricsRegistry.make
        service     <- ZIO
                         .service[LocalityAwareUpload]
                         .provide(
                           ZLayer.succeed(placementReturning(local)),
                           ZLayer.succeed(ingestCounting(localCalls)),
                           ZLayer.succeed(transportCounting(remoteCalls)),
                           ZLayer.succeed[MetricsRegistry](registry),
                           LocalityAwareUpload.instrumented,
                         )
        _           <- service.upload(key, intent, ZStream.fromChunk(Chunk(1.toByte, 2.toByte, 3.toByte, 4.toByte)))
        snapshot    <- registry.snapshot
        decisionKey  = MetricKey(MetricKeys.UploadLocalityDecisionsTotal, Map("route" -> "local"))
      yield assertTrue(
        snapshot.counters.get(decisionKey).contains(1L),
        !snapshot.counters.keys.exists(_.tags.contains("tenant")),
        !snapshot.counters.keys.exists(_.tags.contains("session")),
      )
    },
  )

  private def placementReturning(owner: UploadNode): UploadPlacement = new UploadPlacement:
    override val localNode: UIO[UploadNode]                                           = ZIO.succeed(local)
    override def locate(key: UploadSessionKey): IO[UploadPlacement.Error, UploadNode] = ZIO.succeed(owner)
    override val assignments: IO[UploadPlacement.Error, Chunk[UploadShardAssignment]] = ZIO.succeed(Chunk.empty)

  private def ingestCounting(calls: Ref[Int]): UploadNodeIngest = new UploadNodeIngest:
    override def uploadLocal(
      key: UploadSessionKey,
      intent: UploadIntent,
      bytes: ZStream[Any, Throwable, Byte],
    ): IO[UploadNodeIngest.Error, LocalizedUploadResult] =
      calls.update(_ + 1) *> bytes.runDrain.mapError(UploadNodeIngest.Error.StorageFailure.apply).as(result)

  private def transportCounting(calls: Ref[Int]): UploadNodeTransport = new UploadNodeTransport:
    override def upload(
      owner: UploadNode,
      key: UploadSessionKey,
      intent: UploadIntent,
      bytes: ZStream[Any, Throwable, Byte],
    ): IO[UploadNodeTransport.Error, LocalizedUploadResult] =
      calls
        .update(_ + 1) *> bytes.runDrain
        .mapError(UploadNodeTransport.Error.ConnectionFailure(owner, _))
        .as(result.copy(owner = owner))
