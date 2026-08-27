package graviton.runtime.stores

import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.config.MaintenanceConfig
import graviton.runtime.model.{BlobDescription, BlobListing, BlobStat, BlobWritePlan}
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
        maintenanceEntered   <- Promise.make[Nothing, Unit]
        first                <- ZIO
                                  .scoped(coordinator.operationPermit *> firstEntered.succeed(()) *> releaseFirst.await)
                                  .fork
        _                    <- firstEntered.await
        secondCompleted      <- coordinator.withOperation(ZIO.unit)
        maintenance          <- ZIO
                                  .scoped(coordinator.maintenanceLease *> maintenanceEntered.succeed(()))
                                  .fork
        _                    <- ZIO.yieldNow.repeatN(20)
        enteredBeforeRelease <- maintenanceEntered.isDone
        _                    <- releaseFirst.succeed(())
        _                    <- first.join
        _                    <- maintenance.join
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
        acquired    <- coordinator.withMaintenance(ZIO.succeed(true))
      yield assertTrue(acquired)
    },
    test("waiting maintenance prevents new local operations from overtaking it") {
      for
        coordinator        <- MaintenanceCoordinator.inProcess()
        firstEntered       <- Promise.make[Nothing, Unit]
        releaseFirst       <- Promise.make[Nothing, Unit]
        maintenanceEntered <- Promise.make[Nothing, Unit]
        releaseMaintenance <- Promise.make[Nothing, Unit]
        laterEntered       <- Promise.make[Nothing, Unit]
        first              <- coordinator
                                .withOperation(firstEntered.succeed(()) *> releaseFirst.await)
                                .fork
        _                  <- firstEntered.await
        maintenance        <- coordinator
                                .withMaintenance(maintenanceEntered.succeed(()) *> releaseMaintenance.await)
                                .fork
        _                  <- ZIO.yieldNow.repeatN(20)
        later              <- coordinator.withOperation(laterEntered.succeed(())).fork
        _                  <- ZIO.yieldNow.repeatN(20)
        overtookWaiting    <- laterEntered.isDone
        _                  <- releaseFirst.succeed(())
        _                  <- maintenanceEntered.await
        enteredDuringLease <- laterEntered.isDone
        _                  <- releaseMaintenance.succeed(())
        _                  <- maintenance.join
        _                  <- later.join
        _                  <- first.join
      yield assertTrue(!overtookWaiting, !enteredDuringLease)
    },
    test("coordinated downloads hold the permit for the complete stream lifetime") {
      for
        coordinator        <- MaintenanceCoordinator.inProcess()
        streamEntered      <- Promise.make[Nothing, Unit]
        releaseStream      <- Promise.make[Nothing, Unit]
        maintenanceEntered <- Promise.make[Nothing, Unit]
        delegate            = blockingReadStore(streamEntered, releaseStream)
        store               = new CoordinatedBlobStore(delegate, coordinator)
        download           <- store.get(testBlobKey).runDrain.fork
        _                  <- streamEntered.await
        maintenance        <- ZIO
                                .scoped(coordinator.maintenanceLease *> maintenanceEntered.succeed(()))
                                .fork
        _                  <- ZIO.yieldNow.repeatN(20)
        enteredBeforeEnd   <- maintenanceEntered.isDone
        _                  <- releaseStream.succeed(())
        _                  <- download.join
        _                  <- maintenance.join
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
          maintenanceEntered <- Promise.make[Nothing, Unit]
          operation          <- ZIO
                                  .scoped(first.operationPermit *> operationEntered.succeed(()) *> releaseOperation.await)
                                  .fork
          _                  <- operationEntered.await
          concurrent         <- second.withOperation(ZIO.succeed(true))
          maintenance        <- ZIO
                                  .scoped(second.maintenanceLease *> maintenanceEntered.succeed(()))
                                  .fork
          _                  <- ZIO.yieldNow.repeatN(20)
          enteredBeforeEnd   <- maintenanceEntered.isDone
          _                  <- releaseOperation.succeed(())
          _                  <- operation.join
          _                  <- maintenance.join
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
          exit        <- ZIO.scoped(coordinator.operationPermit).exit
          typedTimeout = exit match
                           case Exit.Failure(cause) =>
                             cause.failureOption.exists(_.isInstanceOf[MaintenanceError.AcquisitionTimedOut])
                           case Exit.Success(_)     => false
          _            = held
        yield assertTrue(typedTimeout)
      }
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
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

  private val testBlobKey: BinaryKey.Blob =
    KeyBits
      .fromString(s"sha-256:${"00" * 32}:1")
      .flatMap(BinaryKey.blob)
      .fold(message => throw new IllegalStateException(message), identity)

  private def blockingReadStore(
    entered: Promise[Nothing, Unit],
    release: Promise[Nothing, Unit],
  ): BlobStore =
    new BlobStore:
      override def put(plan: BlobWritePlan): BlobSink                          = ZSink.fail(new UnsupportedOperationException("unused"))
      override def get(key: BinaryKey.Blob): ZStream[Any, Throwable, Byte]     =
        ZStream.fromZIO(entered.succeed(())).drain ++
          ZStream.fromZIO(release.await).drain ++
          ZStream.succeed(1.toByte)
      override def stat(key: BinaryKey.Blob): Task[Option[BlobStat]]           = ZIO.none
      override def list: Task[Chunk[BlobListing]]                              = ZIO.succeed(Chunk.empty)
      override def inspect(key: BinaryKey.Blob): Task[Option[BlobDescription]] = ZIO.none
      override def delete(key: BinaryKey.Blob): Task[Unit]                     = ZIO.unit
      override def healthCheck: Task[Unit]                                     = ZIO.unit

  private def deleteRecursive(path: java.nio.file.Path): Unit =
    if Files.exists(path) then
      val files = Files.walk(path)
      try
        files.sorted(java.util.Comparator.reverseOrder()).forEach { current =>
          val _ = Files.deleteIfExists(current)
        }
      finally files.close()
