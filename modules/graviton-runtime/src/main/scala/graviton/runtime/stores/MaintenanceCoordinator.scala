package graviton.runtime.stores

import graviton.core.types.RepositoryNamespace
import graviton.runtime.config.MaintenanceConfig
import zio.*

/**
 * Repository-wide reader/writer coordination.
 *
 * Ordinary blob operations hold a shared permit for their complete resource
 * lifetime. Destructive maintenance holds an exclusive lease. Production
 * implementations must coordinate every process that can reach the same
 * manifest and block stores, not only fibers in one runtime.
 */
trait MaintenanceCoordinator:
  def operationPermit: ZIO[Scope, MaintenanceError, Unit]
  def maintenanceLease: ZIO[Scope, MaintenanceError, Unit]
  def healthCheck: IO[MaintenanceError, Unit]

  final def withOperation[A](effect: Task[A]): Task[A] =
    ZIO.scoped(operationPermit *> effect)

  final def withMaintenance[A](effect: Task[A]): Task[A] =
    ZIO.scoped(maintenanceLease *> effect)

sealed abstract class MaintenanceError(message: String, cause: Throwable | Null = null) extends Exception(message, cause)

object MaintenanceError:
  final case class AcquisitionTimedOut(
    namespace: RepositoryNamespace,
    mode: MaintenanceMode,
    timeout: Duration,
  ) extends MaintenanceError(
        s"Timed out after $timeout acquiring ${mode.label} repository coordination for '${namespace.value}'"
      )

  final case class BackendFailure(
    namespace: RepositoryNamespace,
    operation: String,
    underlying: Throwable,
  ) extends MaintenanceError(
        s"Repository coordination '$operation' failed for '${namespace.value}': ${underlying.getMessage}",
        underlying,
      )

enum MaintenanceMode(val label: String):
  case Operation   extends MaintenanceMode("shared operation")
  case Maintenance extends MaintenanceMode("exclusive maintenance")

object MaintenanceCoordinator:
  val service: ZIO[MaintenanceCoordinator, Nothing, MaintenanceCoordinator] =
    ZIO.service[MaintenanceCoordinator]

  /**
   * Compatibility-only coordinator. It preserves old direct constructor and
   * layer behavior but does not provide cross-fiber or cross-process safety.
   */
  val uncoordinated: MaintenanceCoordinator = new MaintenanceCoordinator:
    override val operationPermit: ZIO[Scope, Nothing, Unit]  = ZIO.unit
    override val maintenanceLease: ZIO[Scope, Nothing, Unit] = ZIO.unit
    override val healthCheck: ZIO[Any, Nothing, Unit]        = ZIO.unit

  /** Real fiber-safe coordination for in-memory repositories and tests. */
  def inProcess(config: MaintenanceConfig = MaintenanceConfig.Default): IO[IllegalArgumentException, MaintenanceCoordinator] =
    for
      valid <- ZIO.fromEither(config.validate).mapError(new IllegalArgumentException(_))
      gate  <- LocalMaintenanceGate.make
    yield new MaintenanceCoordinator:
      override val operationPermit: ZIO[Scope, MaintenanceError, Unit] =
        acquireScopedInterruptibly(
          gate.acquireOperation
            .timeoutFail(MaintenanceError.AcquisitionTimedOut(valid.namespace, MaintenanceMode.Operation, valid.acquisitionTimeout))(
              valid.acquisitionTimeout
            )
        )(_ => gate.releaseOperation).unit

      override val maintenanceLease: ZIO[Scope, MaintenanceError, Unit] =
        acquireScopedInterruptibly(
          gate.acquireMaintenance
            .timeoutFail(
              MaintenanceError.AcquisitionTimedOut(valid.namespace, MaintenanceMode.Maintenance, valid.acquisitionTimeout)
            )(valid.acquisitionTimeout)
        )(_ => gate.releaseMaintenance).unit

      override val healthCheck: ZIO[Any, Nothing, Unit] = ZIO.unit

  val inProcessLayer: ZLayer[MaintenanceConfig, IllegalArgumentException, MaintenanceCoordinator] =
    ZLayer.fromZIO(ZIO.service[MaintenanceConfig].flatMap(inProcess))

  val inProcessDefault: ZLayer[Any, IllegalArgumentException, MaintenanceCoordinator] =
    ZLayer.fromZIO(inProcess())

  /**
   * Register a resource finalizer atomically after an interruptible acquire.
   * The acquire action must clean up any partially acquired backend resource
   * when it is interrupted or fails.
   */
  private[graviton] def acquireScopedInterruptibly[E, A](
    acquire: IO[E, A]
  )(
    release: A => UIO[Any]
  ): ZIO[Scope, E, A] =
    ZIO.uninterruptibleMask { restore =>
      restore(acquire).flatMap(value => ZIO.addFinalizer(release(value)).as(value))
    }
