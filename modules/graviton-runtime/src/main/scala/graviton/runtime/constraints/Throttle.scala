package graviton.runtime.constraints

import zio.{Clock, Ref, UIO, ZIO}

final case class Throttle(state: Ref[(Long, Long)], ratePerSecond: Long):
  def take(tokens: Long): ZIO[Clock, Nothing, Boolean] =
    for
      now    <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      result <- state.modify { case (available, lastTick) =>
                  val elapsed   =
                    if lastTick == Long.MinValue || now <= lastTick then 0L
                    else now - lastTick
                  val replenish =
                    if elapsed >= 1000L then ratePerSecond
                    else
                      val whole = (ratePerSecond / 1000L) * elapsed
                      val tail  = ((ratePerSecond % 1000L) * elapsed) / 1000L
                      whole + tail
                  val updated   =
                    if replenish >= ratePerSecond - available then ratePerSecond
                    else available + replenish
                  if tokens <= updated then ((true, now), (updated - tokens, now))
                  // Account for elapsed time even when the requested charge
                  // is denied. Retaining lastTick would let rapid retries add
                  // the same elapsed refill repeatedly and mint tokens.
                  else ((false, now), (updated, now))
                }
    yield result._1

object Throttle:
  def make(ratePerSecond: Long): UIO[Throttle] =
    Ref.make((ratePerSecond, Long.MinValue)).map(Throttle(_, ratePerSecond))
