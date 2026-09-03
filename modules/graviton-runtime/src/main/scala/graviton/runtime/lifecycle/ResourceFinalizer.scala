package graviton.runtime.lifecycle

import zio.{Task, UIO, ZIO}

/** Makes best-effort resource cleanup observable without turning a finalizer failure into a defect. */
object ResourceFinalizer:

  def closeBlocking(resource: String)(close: => Unit): UIO[Unit] =
    run(resource)(ZIO.attemptBlocking(close))

  def run(resource: String)(cleanup: Task[Unit]): UIO[Unit] =
    cleanup.catchAllCause(cause => ZIO.logErrorCause(s"Failed to release $resource", cause))
