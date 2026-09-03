package graviton.runtime.stores

import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.config.MaintenanceConfig
import graviton.runtime.model.{BlobDescription, BlobListing, BlobStat, BlobWritePlan, InventoryCursor, InventoryPage, InventoryPageSize}
import zio.*
import zio.stream.{ZSink, ZStream}
import zio.test.*

import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardOpenOption}

object MaintenanceCoordinatorSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] = suite("MaintenanceCoordinator")(
    test("shared operations complete concurrently while maintenance waits") {
      for
        coordinator          <- MaintenanceCoordinator.inProcess()
        firstEntered         <- Promise.make[Nothing, Unit]
        releaseFirst         <- Promise.make[Nothing, Unit]
        maintenanceStarted   <- Promise.make[Nothing, Unit]
        maintenanceEntered   <- Promise.make[Nothing, Unit]
        first                <- ZIO
                                  .scoped(coordinator.operationPermit *> firstEntered.succeed(()) *> releaseFirst.await)
                                  .fork
        _                    <- firstEntered.await
        secondCompleted      <- coordinator.withOperation(ZIO.unit)
        maintenance          <- (maintenanceStarted.succeed(()) *>
                                  ZIO.scoped(coordinator.maintenanceLease *> maintenanceEntered.succeed(()))).fork
        _                    <- maintenanceStarted.await
        _                    <- settleFibers
        enteredBeforeRelease <- maintenanceEntered.isDone
        _                    <- releaseFirst.succeed(())
        _                    <- first.join
        _                    <- maintenance.join
        _                    <- settleFibers
      yield assertTrue(secondCompleted == (), !enteredBeforeRelease)
    },
    test("interrupting an operation releases its permit") {
      for
        coordinator <- MaintenanceCoordinator.inProcess()
        entered     <- Promise.make[Nothing, Unit]
        operation   <- ZIO
                         .scoped(coordinator.operationPermit *> entered.succeed(()) *> ZIO.never)
                         .fork
        _           <- entered.await
        _           <- operation.interrupt
        _           <- settleFibers
        acquired    <- coordinator.withMaintenance(ZIO.succeed(true))
        _           <- settleFibers
      yield assertTrue(acquired)
    },
    test("waiting maintenance prevents new local operations from overtaking it") {
      for
        coordinator        <- MaintenanceCoordinator.inProcess()
        firstEntered       <- Promise.make[Nothing, Unit]
        releaseFirst       <- Promise.make[Nothing, Unit]
        maintenanceEntered <- Promise.make[Nothing, Unit]
        releaseMaintenance <- Promise.make[Nothing, Unit]
        maintenanceStarted <- Promise.make[Nothing, Unit]
        laterStarted       <- Promise.make[Nothing, Unit]
        laterEntered       <- Promise.make[Nothing, Unit]
        first              <- coordinator
                                .withOperation(firstEntered.succeed(()) *> releaseFirst.await)
                                .fork
        _                  <- firstEntered.await
        maintenance        <- (maintenanceStarted.succeed(()) *>
                                coordinator.withMaintenance(maintenanceEntered.succeed(()) *> releaseMaintenance.await)).fork
        _                  <- maintenanceStarted.await
        _                  <- settleFibers
        later              <- (laterStarted.succeed(()) *> coordinator.withOperation(laterEntered.succeed(()))).fork
        _                  <- laterStarted.await
        _                  <- settleFibers
        overtookWaiting    <- laterEntered.isDone
        _                  <- releaseFirst.succeed(())
        _                  <- maintenanceEntered.await
        enteredDuringLease <- laterEntered.isDone
        _                  <- releaseMaintenance.succeed(())
        _                  <- maintenance.join
        _                  <- later.join
        _                  <- first.join
        _                  <- settleFibers
      yield assertTrue(!overtookWaiting, !enteredDuringLease)
    },
    test("coordinated downloads hold the permit for the complete stream lifetime") {
      for
        coordinator        <- MaintenanceCoordinator.inProcess()
        streamEntered      <- Promise.make[Nothing, Unit]
        releaseStream      <- Promise.make[Nothing, Unit]
        maintenanceStarted <- Promise.make[Nothing, Unit]
        maintenanceEntered <- Promise.make[Nothing, Unit]
        delegate            = blockingReadStore(streamEntered, releaseStream)
        store               = new CoordinatedBlobStore(delegate, coordinator)
        download           <- store.get(testBlobKey).runDrain.fork
        _                  <- streamEntered.await
        maintenance        <- (maintenanceStarted.succeed(()) *>
                                ZIO.scoped(coordinator.maintenanceLease *> maintenanceEntered.succeed(()))).fork
        _                  <- maintenanceStarted.await
        _                  <- settleFibers
        enteredBeforeEnd   <- maintenanceEntered.isDone
        _                  <- releaseStream.succeed(())
        _                  <- download.join
        _                  <- maintenance.join
        _                  <- settleFibers
      yield assertTrue(!enteredBeforeEnd)
    },
    test("filesystem coordinator composes independent instances in one JVM") {
      ZIO.scoped {
        for
          root               <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("graviton-coordination")))(path =>
                                  ZIO.attemptBlocking(deleteRecursive(path)).orDie
                                )
          first              <- FileMaintenanceCoordinator.make(root)
          second             <- FileMaintenanceCoordinator.make(root)
          operationEntered   <- Promise.make[Nothing, Unit]
          releaseOperation   <- Promise.make[Nothing, Unit]
          maintenanceStarted <- Promise.make[Nothing, Unit]
          maintenanceEntered <- Promise.make[Nothing, Unit]
          operation          <- ZIO
                                  .scoped(first.operationPermit *> operationEntered.succeed(()) *> releaseOperation.await)
                                  .fork
          _                  <- operationEntered.await
          concurrent         <- second.withOperation(ZIO.succeed(true))
          maintenance        <- (maintenanceStarted.succeed(()) *>
                                  ZIO.scoped(second.maintenanceLease *> maintenanceEntered.succeed(()))).fork
          _                  <- maintenanceStarted.await
          _                  <- settleFibers
          enteredBeforeEnd   <- maintenanceEntered.isDone
          _                  <- releaseOperation.succeed(())
          _                  <- operation.join
          _                  <- maintenance.join
          _                  <- settleFibers
        yield assertTrue(concurrent, !enteredBeforeEnd)
      }
    },
    test("filesystem coordinator reports a typed timeout behind an external lock") {
      ZIO.scoped {
        for
          root        <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("graviton-coordination-timeout")))(path =>
                           ZIO.attemptBlocking(deleteRecursive(path)).orDie
                         )
          lockPath     = root.resolve("cas").resolve(".maintenance.lock")
          _           <- ZIO.attemptBlocking(Files.createDirectories(lockPath.getParent))
          held        <- ZIO.acquireRelease(
                           ZIO.attemptBlocking {
                             val channel = FileChannel.open(
                               lockPath,
                               StandardOpenOption.CREATE,
                               StandardOpenOption.READ,
                               StandardOpenOption.WRITE,
                             )
                             channel -> channel.lock()
                           }
                         ) { case (channel, lock) =>
                           ZIO.attemptBlocking {
                             lock.release()
                             channel.close()
                           }.orDie
                         }
          config       = MaintenanceConfig(
                           acquisitionTimeout = 250.millis,
                           pollInterval = 25.millis,
                         )
          coordinator <- FileMaintenanceCoordinator.make(root, config)
          waiting     <- ZIO.scoped(coordinator.operationPermit).fork
          _           <- TestClock.adjust(config.acquisitionTimeout + config.pollInterval)
          exit        <- waiting.await
          typedTimeout = exit match
                           case Exit.Failure(cause) =>
                             cause.failureOption.exists(_.isInstanceOf[MaintenanceError.AcquisitionTimedOut])
                           case Exit.Success(_)     => false
          _            = held
        yield assertTrue(typedTimeout)
      }
    },
    test("ZIO Config refines namespaces and rejects invalid timing") {
      val invalidNamespace = ConfigProvider.fromMap(Map("graviton.maintenance.namespace" -> "bad namespace"))
      val invalidTiming    = ConfigProvider.fromMap(
        Map(
          "graviton.maintenance.acquisition-timeout" -> "100 ms",
          "graviton.maintenance.poll-interval"       -> "1 s",
        )
      )

      for
        namespaceExit <- ZIO.withConfigProvider(invalidNamespace)(ZIO.config(MaintenanceConfig.config)).exit
        timingExit    <- ZIO.withConfigProvider(invalidTiming)(ZIO.config(MaintenanceConfig.config)).exit
      yield assertTrue(namespaceExit.isFailure, timingExit.isFailure)
    },
  ) @@ TestAspect.sequential

  /**
   * TestClock.adjust waits for supervised fibers to become suspended before it
   * advances time. That gives acquisition fibers a deterministic scheduling
   * boundary without pretending that repeated scheduler yields are a
   * coordination primitive.
   */
  private val settleFibers: UIO[Unit] =
    TestClock.adjust(1.millis)

  private val testBlobKey: BinaryKey.Blob =
    KeyBits
      .parse(s"sha-256:${"00" * 32}:1")
      .flatMap(BinaryKey.blob)
      .fold(message => throw new IllegalStateException(message), identity)

  private def blockingReadStore(
    entered: Promise[Nothing, Unit],
    release: Promise[Nothing, Unit],
  ): BlobStore =
    new BlobStore:
      override def put(plan: BlobWritePlan): BlobSink                                                                                  =
        ZSink.fail(StoreError.InvalidInput(StoreOperation.PutBlob, "unused in this read-coordination test"))
      override def get(key: BinaryKey.Blob): ZStream[Any, StoreError, Byte]                                                            =
        ZStream.fromZIO(entered.succeed(())).drain ++
          ZStream.fromZIO(release.await).drain ++
          ZStream.succeed(1.toByte)
      override def stat(key: BinaryKey.Blob): IO[StoreError, Option[BlobStat]]                                                         = ZIO.none
      override def inventoryPage(after: Option[InventoryCursor], limit: InventoryPageSize): IO[StoreError, InventoryPage[BlobListing]] =
        ZIO.succeed(InventoryPage(Chunk.empty, None))
      override def inspect(key: BinaryKey.Blob): IO[StoreError, Option[BlobDescription]]                                               = ZIO.none
      override def delete(key: BinaryKey.Blob): IO[StoreError, Unit]                                                                   = ZIO.unit
      override def healthCheck: IO[StoreError, Unit]                                                                                   = ZIO.unit

  private def deleteRecursive(path: java.nio.file.Path): Unit =
    if Files.exists(path) then
      val files = Files.walk(path)
      try
        files.sorted(java.util.Comparator.reverseOrder()).forEach { current =>
          val _ = Files.deleteIfExists(current)
        }
      finally files.close()
