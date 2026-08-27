package graviton.runtime.upload

import zio.*

/** Bounded, reconstructable node-local acceleration state. */
trait UploadHotState:
  def begin(key: UploadSessionKey): UIO[Unit]
  def observe(key: UploadSessionKey, frame: UploadTransportFrame): UIO[Unit]
  def complete(key: UploadSessionKey): UIO[Unit]
  def fail(key: UploadSessionKey): UIO[Unit]
  def evict(key: UploadSessionKey): UIO[Unit]
  def snapshot(key: UploadSessionKey): UIO[Option[UploadHotState.Snapshot]]
  def size: UIO[Int]

object UploadHotState:
  enum Phase:
    case Active, Completed, Failed

  final case class Snapshot(
    key: UploadSessionKey,
    phase: Phase,
    framesSeen: Long,
    bytesSeen: Long,
    startedAtNanos: Long,
    lastActivityNanos: Long,
  )

  final case class Config(maxSessions: Int):
    require(maxSessions > 0, "maxSessions must be positive")

  object Config:
    val Default: Config = Config(maxSessions = 4096)

  private final case class State(values: Map[UploadSessionKey, Snapshot])

  val service: ZIO[UploadHotState, Nothing, UploadHotState] = ZIO.service[UploadHotState]

  def inMemory(config: Config = Config.Default): UIO[UploadHotState] =
    Ref.Synchronized.make(State(Map.empty)).map { ref =>
      new UploadHotState:
        override def begin(key: UploadSessionKey): UIO[Unit] =
          Clock.nanoTime.flatMap { now =>
            ref.update { state =>
              val next = state.values.updated(key, Snapshot(key, Phase.Active, 0L, 0L, now, now))
              State(trim(next, config.maxSessions, preserve = key))
            }
          }

        override def observe(key: UploadSessionKey, frame: UploadTransportFrame): UIO[Unit] =
          Clock.nanoTime.flatMap { now =>
            ref.update { state =>
              val updated = state.values.get(key).map { current =>
                current.copy(
                  framesSeen = saturatingAdd(current.framesSeen, 1L),
                  bytesSeen = saturatingAdd(current.bytesSeen, frame.length.toLong),
                  lastActivityNanos = now,
                )
              }
              State(updated.fold(state.values)(value => state.values.updated(key, value)))
            }
          }

        override def complete(key: UploadSessionKey): UIO[Unit] = setPhase(key, Phase.Completed)
        override def fail(key: UploadSessionKey): UIO[Unit]     = setPhase(key, Phase.Failed)

        private def setPhase(key: UploadSessionKey, phase: Phase): UIO[Unit] =
          Clock.nanoTime.flatMap { now =>
            ref.update(state => State(state.values.updatedWith(key)(_.map(_.copy(phase = phase, lastActivityNanos = now)))))
          }

        override def evict(key: UploadSessionKey): UIO[Unit] =
          ref.update(state => State(state.values - key))

        override def snapshot(key: UploadSessionKey): UIO[Option[Snapshot]] =
          ref.get.map(_.values.get(key))

        override def size: UIO[Int] = ref.get.map(_.values.size)
    }

  val live: ZLayer[Config, Nothing, UploadHotState] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[Config](inMemory))

  val default: ULayer[UploadHotState] =
    ZLayer.fromZIO(inMemory())

  private def saturatingAdd(left: Long, right: Long): Long =
    if left > Long.MaxValue - right then Long.MaxValue else left + right

  private def trim(
    values: Map[UploadSessionKey, Snapshot],
    maximum: Int,
    preserve: UploadSessionKey,
  ): Map[UploadSessionKey, Snapshot] =
    if values.size <= maximum then values
    else
      val removable = values.valuesIterator
        .filterNot(_.key == preserve)
        .toVector
        .sortBy(snapshot => (snapshot.phase == Phase.Active, snapshot.lastActivityNanos))
      removable.headOption.fold(values)(snapshot => values - snapshot.key)
