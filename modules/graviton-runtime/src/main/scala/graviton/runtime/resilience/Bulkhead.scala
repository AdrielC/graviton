package graviton.runtime.resilience

import zio.*

/**
 * Concurrency bulkhead (simple semaphore gate).
 *
 * Use this to cap concurrent work against a constrained resource (disk, DB, remote service).
 *
 * @note `maxConcurrent` must be >= 1.
 */
final case class Bulkhead(private val semaphore: Semaphore):
  def withPermit[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
    semaphore.withPermit(zio)

object Bulkhead:

  /** Create a bulkhead with the given maximum concurrency (must be >= 1). */
  def make(maxConcurrent: Long): IO[IllegalArgumentException, Bulkhead] =
    if maxConcurrent < 1 then ZIO.fail(new IllegalArgumentException(s"maxConcurrent must be >= 1, got $maxConcurrent"))
    else Semaphore.make(maxConcurrent).map(Bulkhead(_))
