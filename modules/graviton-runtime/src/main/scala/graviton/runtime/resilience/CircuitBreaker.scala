package graviton.runtime.resilience

import zio.*

import java.time.Instant

sealed trait CircuitState derives CanEqual
object CircuitState:
  final case class Closed(failures: Int) extends CircuitState
  final case class Open(until: Instant)  extends CircuitState
  case object HalfOpen                   extends CircuitState

final case class CircuitBreakerRejected(message: String) extends Exception(message)

/**
 * Minimal circuit breaker:
 * - CLOSED: allow; count failures; open when threshold reached.
 * - OPEN: reject until timeout elapses.
 * - HALF_OPEN: allow a probe; on success close; on failure re-open.
 */
final class CircuitBreaker private (
  state: Ref.Synchronized[CircuitState],
  maxFailures: Int,
  openFor: Duration,
):

  def protect[R, E, A](zio: ZIO[R, E, A]): ZIO[R & Clock, E | CircuitBreakerRejected, A] =
    for
      now  <- Clock.instant
      gate <- state.get.flatMap {
                case CircuitState.Open(until) if now.isBefore(until) =>
                  ZIO.fail(CircuitBreakerRejected(s"circuit breaker open until $until"))
                case CircuitState.Open(_)                            =>
                  state.set(CircuitState.HalfOpen) *> ZIO.succeed(())
                case CircuitState.HalfOpen                           =>
                  ZIO.succeed(())
                case CircuitState.Closed(_)                          =>
                  ZIO.succeed(())
              }
      out  <- zio.tapError(_ => onFailure).tap(_ => onSuccess)
    yield out

  private def onSuccess: URIO[Clock, Unit] =
    state.set(CircuitState.Closed(failures = 0)).unit

  private def onFailure: URIO[Clock, Unit] =
    Clock.instant.flatMap { now =>
      state.modifyZIO {
        case CircuitState.Closed(n)   =>
          val next =
            if (n + 1) >= maxFailures then CircuitState.Open(now.plusMillis(openFor.toMillis))
            else CircuitState.Closed(n + 1)
          ZIO.succeed(() -> next)
        case CircuitState.HalfOpen    =>
          ZIO.succeed(() -> CircuitState.Open(now.plusMillis(openFor.toMillis)))
        case s @ CircuitState.Open(_) =>
          ZIO.succeed(() -> s)
      }.unit
    }

object CircuitBreaker:
  def make(maxFailures: Int, openFor: Duration): UIO[CircuitBreaker] =
    Ref.Synchronized
      .make[CircuitState](CircuitState.Closed(0))
      .map(ref => new CircuitBreaker(ref, maxFailures, openFor))
