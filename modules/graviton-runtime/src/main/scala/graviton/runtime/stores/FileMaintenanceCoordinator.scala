package graviton.runtime.stores

import graviton.runtime.config.MaintenanceConfig
import zio.*

import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * Fiber-safe and cross-process maintenance coordination for one filesystem
 * repository root.
 *
 * A local writer-preferring reader/writer gate prevents maintenance starvation
 * and lets all local operations share one OS-level shared lock. The file lock
 * is the cross-process authority: ordinary operations take a shared lock and
 * maintenance takes the exclusive form over the same byte range.
 */
final class FileMaintenanceCoordinator private (
  lockPath: Path,
  config: MaintenanceConfig,
  state: FileMaintenanceCoordinator.LocalState,
) extends MaintenanceCoordinator:
  import FileMaintenanceCoordinator.*

  private val local  = state.local
  private val shared = state.shared
  private val guard  = state.guard

  override val operationPermit: ZIO[Scope, MaintenanceError, Unit] =
    localPermit(MaintenanceMode.Operation) *>
      MaintenanceCoordinator.acquireScopedInterruptibly(acquireShared)(_ => releaseShared)

  override val maintenanceLease: ZIO[Scope, MaintenanceError, Unit] =
    localPermit(MaintenanceMode.Maintenance) *>
      MaintenanceCoordinator
        .acquireScopedInterruptibly(acquireFileLock(MaintenanceMode.Maintenance, shared = false))(releaseFileLock)
        .unit

  override val healthCheck: IO[MaintenanceError, Unit] =
    openChannel.flatMap(channel => closeChannel(channel).as(())).mapError(identity)

  private def localPermit(mode: MaintenanceMode): ZIO[Scope, MaintenanceError, Unit] =
    mode match
      case MaintenanceMode.Operation   =>
        MaintenanceCoordinator
          .acquireScopedInterruptibly(
            local.acquireOperation.timeoutFail(
              MaintenanceError.AcquisitionTimedOut(config.namespace, mode, config.acquisitionTimeout)
            )(config.acquisitionTimeout)
          )(_ => local.releaseOperation)
          .unit
      case MaintenanceMode.Maintenance =>
        MaintenanceCoordinator
          .acquireScopedInterruptibly(
            local.acquireMaintenance.timeoutFail(
              MaintenanceError.AcquisitionTimedOut(config.namespace, mode, config.acquisitionTimeout)
            )(config.acquisitionTimeout)
          )(_ => local.releaseMaintenance)
          .unit

  private def acquireShared: IO[MaintenanceError, Unit] =
    guard.withPermit {
      shared.get.flatMap {
        case Some(current) =>
          shared.set(Some(current.copy(users = java.lang.Math.addExact(current.users, 1))))
        case None          =>
          acquireFileLock(MaintenanceMode.Operation, shared = true)
            .flatMap(held => shared.set(Some(SharedLock(held, users = 1))))
      }
    }

  private def releaseShared: UIO[Unit] =
    guard.withPermit {
      shared.get.flatMap {
        case Some(current) if current.users > 1 =>
          shared.set(Some(current.copy(users = current.users - 1)))
        case Some(current)                      =>
          releaseFileLock(current.held) *> shared.set(None)
        case None                               =>
          ZIO.logError(s"Filesystem coordination refcount underflow at $lockPath")
      }
    }

  private def acquireFileLock(mode: MaintenanceMode, shared: Boolean): IO[MaintenanceError, HeldFileLock] =
    ZIO.uninterruptibleMask { restore =>
      openChannel.flatMap { channel =>
        restore(waitForLock(channel, mode, shared)).exit.flatMap {
          case Exit.Success(lock)  => ZIO.succeed(HeldFileLock(channel, lock))
          case Exit.Failure(cause) =>
            closeChannel(channel) *> ZIO.refailCause(cause)
        }
      }
    }

  private def waitForLock(
    channel: FileChannel,
    mode: MaintenanceMode,
    shared: Boolean,
  ): IO[MaintenanceError, FileLock] =
    def loop: IO[MaintenanceError, FileLock] =
      tryLock(channel, mode, shared).flatMap {
        case Some(lock) => ZIO.succeed(lock)
        case None       => ZIO.sleep(config.pollInterval) *> ZIO.suspendSucceed(loop)
      }

    loop.timeoutFail(MaintenanceError.AcquisitionTimedOut(config.namespace, mode, config.acquisitionTimeout))(
      config.acquisitionTimeout
    )

  private def tryLock(
    channel: FileChannel,
    mode: MaintenanceMode,
    shared: Boolean,
  ): IO[MaintenanceError, Option[FileLock]] =
    ZIO
      .attemptBlocking(Option(channel.tryLock(0L, Long.MaxValue, shared)))
      .catchSome { case _: OverlappingFileLockException => ZIO.none }
      .mapError(error => MaintenanceError.BackendFailure(config.namespace, s"try ${mode.label} file lock", error))

  private def openChannel: IO[MaintenanceError, FileChannel] =
    ZIO
      .attemptBlocking {
        Files.createDirectories(lockPath.getParent)
        FileChannel.open(
          lockPath,
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE,
        )
      }
      .mapError(error => MaintenanceError.BackendFailure(config.namespace, "open filesystem lock", error))

  private def releaseFileLock(held: HeldFileLock): UIO[Unit] =
    ZIO
      .attemptBlocking(held.lock.release())
      .ensuring(closeChannel(held.channel))
      .catchAll(error => ZIO.logError(s"Failed to release filesystem coordination at $lockPath: ${error.getMessage}"))

  private def closeChannel(channel: FileChannel): UIO[Unit] =
    ZIO
      .attemptBlocking(channel.close())
      .catchAll(error => ZIO.logError(s"Failed to close filesystem coordination channel at $lockPath: ${error.getMessage}"))

object FileMaintenanceCoordinator:
  private final case class HeldFileLock(channel: FileChannel, lock: FileLock)
  private final case class SharedLock(held: HeldFileLock, users: Int)
  private final case class LocalState(
    local: LocalMaintenanceGate,
    shared: Ref[Option[SharedLock]],
    guard: Semaphore,
  )

  /**
   * JVM-local instances for the same normalized path must share the same
   * refcounted OS lock. Otherwise Java rejects overlapping shared FileLocks
   * even though the operating system permits them across processes.
   */
  private val localStates: Ref.Synchronized[Map[Path, LocalState]] =
    Unsafe.unsafe { implicit unsafe =>
      Ref.Synchronized.unsafe.make(Map.empty)
    }

  def make(
    repositoryRoot: Path,
    config: MaintenanceConfig = MaintenanceConfig.Default,
  ): IO[IllegalArgumentException, MaintenanceCoordinator] =
    for
      valid <- ZIO.fromEither(config.validate).mapError(new IllegalArgumentException(_))
      root   = repositoryRoot.toAbsolutePath.normalize()
      path   = root.resolve("cas").resolve(".maintenance.lock")
      state <- localStates.modifyZIO { current =>
                 current.get(path) match
                   case Some(existing) => ZIO.succeed(existing -> current)
                   case None           =>
                     for
                       local  <- LocalMaintenanceGate.make
                       shared <- Ref.make(Option.empty[SharedLock])
                       guard  <- Semaphore.make(1L)
                       created = LocalState(local, shared, guard)
                     yield created -> current.updated(path, created)
               }
    yield new FileMaintenanceCoordinator(path, valid, state)

  def layer(
    repositoryRoot: Path,
    config: MaintenanceConfig,
  ): ZLayer[Any, IllegalArgumentException, MaintenanceCoordinator] =
    ZLayer.fromZIO(make(repositoryRoot, config))

  def configuredLayer(
    repositoryRoot: Path
  ): ZLayer[MaintenanceConfig, IllegalArgumentException, MaintenanceCoordinator] =
    ZLayer.fromZIO(ZIO.service[MaintenanceConfig].flatMap(make(repositoryRoot, _)))
