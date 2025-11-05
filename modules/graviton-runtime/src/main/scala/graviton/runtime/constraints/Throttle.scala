package graviton.runtime.constraints

import zio.{Clock, Ref, UIO, ZIO}

final case class Throttle(state: Ref[(Long, Long)], ratePerSecond: Long):
  def take(tokens: Long): ZIO[Clock, Nothing, Boolean] =
    for
      now    <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      result <- state.modify { case (available, lastTick) =>
                  val elapsed   = now - lastTick
                  val replenish = (elapsed * ratePerSecond) / 1000
                  val updated   = (available + replenish).min(ratePerSecond)
                  if tokens <= updated then ((true, now), (updated - tokens, now))
                  else ((false, lastTick), (updated, lastTick))
                }
    yield result._1

object Throttle:
  def make(ratePerSecond: Long): UIO[Throttle] =
    Ref.make((ratePerSecond, 0L)).map(Throttle(_, ratePerSecond))
