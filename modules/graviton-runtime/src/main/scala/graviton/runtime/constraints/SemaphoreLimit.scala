package graviton.runtime.constraints

import zio.{Semaphore, UIO, ZIO}

final case class SemaphoreLimit(semaphore: Semaphore):
  def withPermit[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] = semaphore.withPermit(zio)

object SemaphoreLimit:
  def make(permits: Long): UIO[SemaphoreLimit] = Semaphore.make(permits).map(SemaphoreLimit(_))
