package graviton.backend.laws

import graviton.runtime.config.{BlockPersistenceConfig, TransferMemoryConfig}
import graviton.runtime.stores.*
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object CrashConsistencyLawsSpec extends ZIOSpecDefault:
  override def spec =
    suite("published crash consistency laws")(
      CrashConsistencyLaws.suite("filesystem CAS")(filesystemBackend),
      suite("fault controller")(
        test("delays deterministically under TestClock") {
          val point = FaultPoint(StoreOperation.HealthCheck, FaultPhase.Before)
          for
            plan       <- ZIO.fromEither(
                            FaultPlan.single(FaultRule(point, FaultOccurrence.First, FaultAction.Delay(5.seconds)))
                          )
            controller <- FaultController.make(plan)
            fiber      <- controller.check(point).fork
            _          <- TestClock.adjust(4.seconds)
            waiting    <- fiber.poll
            _          <- TestClock.adjust(1.second)
            _          <- fiber.join
          yield assertTrue(waiting.isEmpty)
        },
        test("interrupt action interrupts only the calling fiber") {
          val point = FaultPoint(StoreOperation.PutBlock, FaultPhase.Before)
          for
            plan        <- ZIO.fromEither(FaultPlan.single(FaultRule(point, FaultOccurrence.First, FaultAction.Interrupt)))
            controller  <- FaultController.make(plan)
            interrupted <- controller.check(point).exit
            next        <- controller.check(point).exit
          yield assertTrue(interrupted.isInterrupted, next.isSuccess)
        },
        test("concurrent occurrence accounting triggers exactly once") {
          val point = FaultPoint(StoreOperation.GetBlock, FaultPhase.Before)
          for
            plan       <- ZIO.fromEither(
                            FaultPlan.single(
                              FaultRule(
                                point,
                                FaultOccurrence.applyUnsafe(100),
                                FaultAction.Fail(InjectedStoreFailure.Conflict),
                              )
                            )
                          )
            controller <- FaultController.make(plan)
            results    <- ZIO.foreachPar(1 to 200)(_ => controller.check(point).either)
            count      <- controller.occurrenceCount(point)
          yield assertTrue(results.count(_.isLeft) == 1, count == 200)
        },
        test("trace retention is bounded") {
          val point = FaultPoint(StoreOperation.Inventory, FaultPhase.Before)
          for
            controller <- FaultController.make(FaultPlan.empty, FaultTraceCapacity.applyUnsafe(3))
            _          <- ZIO.foreachDiscard(1 to 10)(_ => controller.check(point))
            events     <- controller.events
          yield assertTrue(
            events.map(_.sequence) == Chunk(8L, 9L, 10L),
            events.forall(_.action.isEmpty),
          )
        },
        test("fault plan validation rejects duplicate triggers and non-positive delays") {
          val point        = FaultPoint(StoreOperation.PutBlock, FaultPhase.Before)
          val occurrence   = FaultOccurrence.First
          val duplicate    = FaultPlan.make(
            Chunk(
              FaultRule(point, occurrence, FaultAction.Interrupt),
              FaultRule(point, occurrence, FaultAction.Fail(InjectedStoreFailure.Conflict)),
            )
          )
          val invalidDelay = FaultPlan.single(FaultRule(point, occurrence, FaultAction.Delay(Duration.Zero)))
          assertTrue(
            duplicate.left.exists(_.isInstanceOf[FaultPlanError.DuplicateTrigger]),
            invalidDelay.left.exists(_.isInstanceOf[FaultPlanError.InvalidDelay]),
          )
        },
        test("a pre-persist failure cancels a large lazy source without draining it") {
          val point = FaultPoint(StoreOperation.PutBlock, FaultPhase.Before)
          for
            plan     <- ZIO.fromEither(
                          FaultPlan.single(
                            FaultRule(point, FaultOccurrence.First, FaultAction.Fail(InjectedStoreFailure.Conflict))
                          )
                        )
            pulls    <- Ref.make(0)
            exit     <- ZIO.scoped {
                          filesystemBackend.open(plan).flatMap { fixture =>
                            fixture.current.flatMap { store =>
                              val chunk  = Chunk.fill(64 * 1024)(0x31.toByte)
                              val source = ZStream
                                .fromIterable(0 until 16384)
                                .mapZIO(_ => pulls.updateAndGet(_ + 1).as(chunk))
                                .flatMap(ZStream.fromChunk)
                              source.run(store.put()).exit
                            }
                          }
                        }
            observed <- pulls.get
          yield assertTrue(exit.isFailure, observed < 128)
        },
      ),
    ) @@ TestAspect.sequential

  private val filesystemBackend: CrashBackend =
    new CrashBackend:
      override def open(plan: FaultPlan): ZIO[Scope, StoreError, CrashStoreFixture] =
        for
          root            <- ZIO.acquireRelease(
                               ZIO
                                 .attemptBlocking(Files.createTempDirectory("graviton-crash-laws-"))
                                 .mapError(StoreError.fromThrowable(StoreOperation.PutBlob, StoreBackend.Filesystem))
                             )(deleteTree)
          faultController <- FaultController.make(plan)
          initial         <- buildStore(root, faultController)
          currentRef      <- Ref.make(initial)
        yield new CrashStoreFixture:
          override def current: UIO[BlobStore]       = currentRef.get
          override def restart: IO[StoreError, Unit] = buildStore(root, faultController).flatMap(currentRef.set)
          override val controller: FaultController   = faultController

  private def buildStore(root: Path, controller: FaultController): IO[StoreError, BlobStore] =
    for
      coordinator <- FileMaintenanceCoordinator
                       .make(root)
                       .mapError(error => StoreError.InvalidInput(StoreOperation.HealthCheck, error.getMessage))
      budget      <- TransferBudget.make(TransferMemoryConfig.Default)
      blocks       = new FaultingBlockStore(new FsBlockStore(root), controller)
      manifests    = new FaultingBlobManifestRepo(new FsBlobManifestRepo(root), controller)
      raw          = new CasBlobStore(
                       blocks,
                       manifests,
                       persistenceConfig = BlockPersistenceConfig.default,
                       transferBudget = budget,
                     )
    yield new CoordinatedBlobStore(raw, coordinator)

  private def deleteTree(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path) then
        val paths = Files.walk(path)
        try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
        finally paths.close()
    }.orDie
