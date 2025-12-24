package graviton.runtime.resilience

import zio.*

/**
 * Concurrency bulkhead (simple semaphore gate).
 *
 * Use this to cap concurrent work against a constrained resource (disk, DB, remote service).
 */
final case class Bulkhead(private val semaphore: Semaphore):
  def withPermit[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
    semaphore.withPermit(zio)

object Bulkhead:
  def make(maxConcurrent: Long): UIO[Bulkhead] =
    Semaphore.make(maxConcurrent).map(Bulkhead(_))
