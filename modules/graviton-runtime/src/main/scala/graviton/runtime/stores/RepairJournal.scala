package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import zio.*
import zio.stream.ZStream

import java.time.Instant

/** Durable progress and unresolved-failure state for background convergence. */
trait RepairJournal:
  def loadCursor: IO[StoreError, Long]
  def checkpoint(nextOffset: Long): IO[StoreError, Unit]
  def recordFailure(key: BinaryKey.Block, error: StoreError, failedAt: Instant): IO[StoreError, Unit]
  def resolve(key: BinaryKey.Block): IO[StoreError, Unit]
  def deadLetters: ZStream[Any, StoreError, RepairDeadLetter]
  def healthCheck: IO[StoreError, Unit]

final case class RepairDeadLetter(
  key: BinaryKey.Block,
  attempts: Long,
  lastError: String,
  lastFailedAt: Instant,
)

object RepairJournal:
  val service: ZIO[RepairJournal, Nothing, RepairJournal] = ZIO.service[RepairJournal]

  def inMemory: UIO[RepairJournal] =
    for
      cursor   <- Ref.make(0L)
      failures <- Ref.make(Map.empty[BinaryKey.Block, RepairDeadLetter])
    yield new RepairJournal:
      override val loadCursor: IO[StoreError, Long] = cursor.get

      override def checkpoint(nextOffset: Long): IO[StoreError, Unit] =
        if nextOffset < 0L then ZIO.fail(StoreError.InvalidInput(StoreOperation.Repair, "repair cursor must be non-negative"))
        else cursor.set(nextOffset)

      override def recordFailure(key: BinaryKey.Block, error: StoreError, failedAt: Instant): IO[StoreError, Unit] =
        failures.update { current =>
          val attempts = current.get(key).fold(1L)(entry => if entry.attempts == Long.MaxValue then Long.MaxValue else entry.attempts + 1L)
          current.updated(key, RepairDeadLetter(key, attempts, RepairJournal.detail(error), failedAt))
        }

      override def resolve(key: BinaryKey.Block): IO[StoreError, Unit]     = failures.update(_ - key)
      override val deadLetters: ZStream[Any, StoreError, RepairDeadLetter] =
        ZStream.fromZIO(failures.get).flatMap(values => ZStream.fromIterable(values.values))
      override val healthCheck: IO[StoreError, Unit]                       = ZIO.unit

  private[graviton] def detail(error: StoreError): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName).take(2048)
