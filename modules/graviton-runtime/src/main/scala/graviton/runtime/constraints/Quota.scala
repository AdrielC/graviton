package graviton.runtime.constraints

import zio.{Ref, UIO, ZIO}

final case class Quota(state: Ref[Long], limit: Long):
  def take(amount: Long): ZIO[Any, Nothing, Boolean] =
    state.modify { used =>
      val updated = used + amount
      if updated <= limit then (true, updated) else (false, used)
    }

object Quota:
  def make(limit: Long): UIO[Quota] = Ref.make(0L).map(Quota(_, limit))
