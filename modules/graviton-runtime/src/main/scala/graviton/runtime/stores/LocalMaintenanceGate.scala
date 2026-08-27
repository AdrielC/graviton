package graviton.runtime.stores

import zio.*
import zio.stm.{TRef, ZSTM}

/**
 * Fiber-agnostic, writer-preferring reader/writer gate.
 *
 * Unlike a reentrant lock, permits may be released by a scope finalizer fiber
 * different from the fiber that won a timeout race during acquisition. That
 * property is required by interruptible, timeout-bounded scoped resources.
 */
private[graviton] final class LocalMaintenanceGate private (
  state: TRef[LocalMaintenanceGate.State]
):
  val acquireOperation: UIO[Unit] =
    state.get.flatMap { current =>
      if !current.maintenance && current.waitingMaintenance == 0L then
        val next = current.copy(operations = java.lang.Math.addExact(current.operations, 1L))
        state.set(next)
      else ZSTM.retry
    }.commit

  val releaseOperation: UIO[Unit] =
    state.modify { current =>
      if current.operations <= 0L then throw new IllegalStateException("Repository operation permit underflow")
      () -> current.copy(operations = current.operations - 1L)
    }.commit

  val acquireMaintenance: UIO[Unit] =
    ZIO.uninterruptibleMask { restore =>
      val announce =
        state.update(current => current.copy(waitingMaintenance = java.lang.Math.addExact(current.waitingMaintenance, 1L))).commit
      val acquire  = state.get.flatMap { current =>
        if current.operations == 0L && !current.maintenance then
          state.set(
            current.copy(
              maintenance = true,
              waitingMaintenance = current.waitingMaintenance - 1L,
            )
          )
        else ZSTM.retry
      }.commit
      val cancel   = state
        .update(current => current.copy(waitingMaintenance = math.max(0L, current.waitingMaintenance - 1L)))
        .commit

      announce *> restore(acquire).onInterrupt(cancel)
    }

  val releaseMaintenance: UIO[Unit] =
    state.modify { current =>
      if !current.maintenance then throw new IllegalStateException("Repository maintenance lease underflow")
      () -> current.copy(maintenance = false)
    }.commit

private[graviton] object LocalMaintenanceGate:
  private final case class State(
    operations: Long,
    maintenance: Boolean,
    waitingMaintenance: Long,
  )

  private val Empty: State = State(operations = 0L, maintenance = false, waitingMaintenance = 0L)

  val make: UIO[LocalMaintenanceGate] =
    TRef.make(Empty).commit.map(new LocalMaintenanceGate(_))
