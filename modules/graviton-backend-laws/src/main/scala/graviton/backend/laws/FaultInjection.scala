package graviton.backend.laws

import graviton.core.RefinedTypeExt
import graviton.core.keys.BinaryKey
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.model.*
import graviton.runtime.stores.*
import io.github.iltotore.iron.constraint.numeric
import zio.*
import zio.stream.*

import java.time.Instant

type FaultOccurrence = FaultOccurrence.T
object FaultOccurrence extends RefinedTypeExt[Int, numeric.GreaterEqual[1]]:
  val First: FaultOccurrence = applyUnsafe(1)

type FaultTraceCapacity = FaultTraceCapacity.T
object FaultTraceCapacity extends RefinedTypeExt[Int, numeric.GreaterEqual[1] & numeric.LessEqual[65536]]:
  val Default: FaultTraceCapacity = applyUnsafe(4096)

type FaultByteLimit = FaultByteLimit.T
object FaultByteLimit extends RefinedTypeExt[Long, numeric.GreaterEqual[0L]]

enum FaultPhase:
  case Before
  case After

final case class FaultPoint(operation: StoreOperation, phase: FaultPhase)

enum InjectedStoreFailure:
  case Unavailable(backend: StoreBackend)
  case PermissionDenied(backend: StoreBackend)
  case CapacityExceeded(limitBytes: FaultByteLimit)
  case CorruptData
  case Conflict

  private[laws] def toStoreError(operation: StoreOperation): StoreError = this match
    case Unavailable(backend)      =>
      StoreError.Unavailable(operation, backend, InjectedStoreFailure.FaultException("injected unavailable backend"))
    case PermissionDenied(backend) => StoreError.PermissionDenied(operation, backend)
    case CapacityExceeded(limit)   => StoreError.CapacityExceeded(operation, limit.value, None)
    case CorruptData               => StoreError.CorruptData(operation, "injected corrupt storage data")
    case Conflict                  => StoreError.Conflict(operation, "injected storage conflict")

object InjectedStoreFailure:
  private final case class FaultException(message: String) extends Exception(message)

enum FaultAction:
  case Fail(failure: InjectedStoreFailure)
  case Delay(duration: Duration)
  case Interrupt

final case class FaultRule(
  point: FaultPoint,
  occurrence: FaultOccurrence,
  action: FaultAction,
)

sealed abstract class FaultPlanError(message: String) extends Exception(message)
object FaultPlanError:
  final case class TooManyRules(actual: Int) extends FaultPlanError(s"fault plan has $actual rules; maximum is ${FaultPlan.MaxRules}")
  final case class DuplicateTrigger(point: FaultPoint, occurrence: FaultOccurrence)
      extends FaultPlanError(s"fault plan repeats ${point.operation}/${point.phase} occurrence ${occurrence.value}")
  final case class InvalidDelay(point: FaultPoint, occurrence: FaultOccurrence)
      extends FaultPlanError(s"fault delay at ${point.operation}/${point.phase} occurrence ${occurrence.value} must be positive")

final class FaultPlan private (val rules: Chunk[FaultRule])

object FaultPlan:
  val MaxRules         = 1024
  val empty: FaultPlan = new FaultPlan(Chunk.empty)

  def single(rule: FaultRule): Either[FaultPlanError, FaultPlan] =
    make(Chunk.single(rule))

  def make(rules: Chunk[FaultRule]): Either[FaultPlanError, FaultPlan] =
    if rules.length > MaxRules then Left(FaultPlanError.TooManyRules(rules.length))
    else
      rules.collectFirst {
        case rule @ FaultRule(point, occurrence, FaultAction.Delay(duration)) if duration <= Duration.Zero =>
          FaultPlanError.InvalidDelay(point, occurrence)
      } match
        case Some(error) => Left(error)
        case None        =>
          rules
            .groupBy(rule => rule.point -> rule.occurrence)
            .collectFirst {
              case ((point, occurrence), duplicates) if duplicates.lengthCompare(1) > 0 =>
                FaultPlanError.DuplicateTrigger(point, occurrence)
            } match
            case Some(error) => Left(error)
            case None        => Right(new FaultPlan(rules))

final case class FaultEvent(
  sequence: Long,
  point: FaultPoint,
  occurrence: Int,
  action: Option[FaultAction],
)

/** Deterministic, bounded fault scheduler used only by qualification tests. */
trait FaultController:
  def check(point: FaultPoint): IO[StoreError, Unit]
  def events: UIO[Chunk[FaultEvent]]
  def occurrenceCount(point: FaultPoint): UIO[Int]

object FaultController:
  def make(
    plan: FaultPlan,
    traceCapacity: FaultTraceCapacity = FaultTraceCapacity.Default,
  ): UIO[FaultController] =
    Ref.Synchronized
      .make(State(Map.empty, Vector.empty, nextSequence = 1L))
      .map { state =>
        new FaultController:
          override def check(point: FaultPoint): IO[StoreError, Unit] =
            state
              .modify { current =>
                val occurrence = current.counts.getOrElse(point, 0) + 1
                val action     = plan.rules.collectFirst {
                  case FaultRule(`point`, target, action) if target.value == occurrence => action
                }
                val event      = FaultEvent(current.nextSequence, point, occurrence, action)
                val appended   = current.events :+ event
                val retained   =
                  if appended.length <= traceCapacity.value then appended else appended.drop(appended.length - traceCapacity.value)
                action -> current.copy(
                  counts = current.counts.updated(point, occurrence),
                  events = retained,
                  nextSequence = current.nextSequence + 1L,
                )
              }
              .flatMap {
                case None                            => ZIO.unit
                case Some(FaultAction.Fail(failure)) => ZIO.fail(failure.toStoreError(point.operation))
                case Some(FaultAction.Delay(delay))  => ZIO.sleep(delay)
                case Some(FaultAction.Interrupt)     => ZIO.interrupt
              }

          override def events: UIO[Chunk[FaultEvent]] =
            state.get.map(current => Chunk.fromIterable(current.events))

          override def occurrenceCount(point: FaultPoint): UIO[Int] =
            state.get.map(_.counts.getOrElse(point, 0))
      }

  private final case class State(
    counts: Map[FaultPoint, Int],
    events: Vector[FaultEvent],
    nextSequence: Long,
  )

  private[laws] def before(operation: StoreOperation): FaultPoint = FaultPoint(operation, FaultPhase.Before)
  private[laws] def after(operation: StoreOperation): FaultPoint  = FaultPoint(operation, FaultPhase.After)

  private[laws] def around[A](controller: FaultController, operation: StoreOperation)(effect: IO[StoreError, A]): IO[StoreError, A] =
    controller.check(before(operation)) *> effect <* controller.check(after(operation))

  private[laws] def stream(
    controller: FaultController,
    operation: StoreOperation,
    source: => ZStream[Any, StoreError, Byte],
  ): ZStream[Any, StoreError, Byte] =
    ZStream.unwrap(
      controller
        .check(before(operation))
        .as(source ++ ZStream.fromZIO(controller.check(after(operation))).drain)
    )

/** Inject faults around one physical block-store operation boundary. */
final class FaultingBlockStore(
  underlying: BlockStore,
  controller: FaultController,
) extends BlockStore:
  override def putBlocks(plan: BlockWritePlan = BlockWritePlan()): BlockSink =
    ZSink.unwrap(
      controller
        .check(FaultController.before(StoreOperation.PutBlock))
        .as(underlying.putBlocks(plan).mapZIO(result => controller.check(FaultController.after(StoreOperation.PutBlock)).as(result)))
    )

  override def putBlock(block: CanonicalBlock, plan: BlockWritePlan = BlockWritePlan()): IO[StoreError, StoredBlock] =
    FaultController.around(controller, StoreOperation.PutBlock)(underlying.putBlock(block, plan))

  override def get(key: BinaryKey.Block): ZStream[Any, StoreError, Byte] =
    FaultController.stream(controller, StoreOperation.GetBlock, underlying.get(key))

  override def exists(key: BinaryKey.Block): IO[StoreError, Boolean] =
    FaultController.around(controller, StoreOperation.ExistsBlock)(underlying.exists(key))

  override def healthCheck: IO[StoreError, Unit] =
    FaultController.around(controller, StoreOperation.HealthCheck)(underlying.healthCheck)

/** Inject faults without materializing a manifest-entry stream. */
final class FaultingBlobManifestRepo(
  underlying: BlobManifestRepo,
  controller: FaultController,
) extends BlobManifestRepo:
  override def put(blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): IO[StoreError, Unit] =
    FaultController.around(controller, StoreOperation.PutManifest)(underlying.put(blob, manifest, ingestedAt))

  override def putStream(
    blob: BinaryKey.Blob,
    totalSize: FileSize,
    blockCount: Int,
    entries: ZStream[Any, StoreError, ManifestEntry],
    ingestedAt: Instant,
  ): IO[StoreError, Unit] =
    FaultController.around(controller, StoreOperation.PutManifest)(
      underlying.putStream(blob, totalSize, blockCount, entries, ingestedAt)
    )

  override def get(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifest]] =
    FaultController.around(controller, StoreOperation.GetManifest)(underlying.get(blob))

  override def getSummary(blob: BinaryKey.Blob): IO[StoreError, Option[StoredManifestSummary]] =
    FaultController.around(controller, StoreOperation.GetManifest)(underlying.getSummary(blob))

  override def inventoryPage(
    after: Option[InventoryCursor],
    limit: InventoryPageSize,
  ): IO[StoreError, InventoryPage[(BinaryKey.Blob, StoredManifestSummary)]] =
    FaultController.around(controller, StoreOperation.Inventory)(underlying.inventoryPage(after, limit))

  override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, StoreError, graviton.runtime.streaming.BlobStreamer.BlockRef] =
    ZStream.unwrap(
      controller
        .check(FaultController.before(StoreOperation.GetManifest))
        .as(
          underlying.streamBlockRefs(blob) ++
            ZStream.fromZIO(controller.check(FaultController.after(StoreOperation.GetManifest))).drain
        )
    )

  override def streamBlockRefsRange(
    blob: BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, StoreError, graviton.runtime.streaming.BlobStreamer.RangedBlockRef] =
    ZStream.unwrap(
      controller
        .check(FaultController.before(StoreOperation.GetManifest))
        .as(
          underlying.streamBlockRefsRange(blob, start, length) ++
            ZStream.fromZIO(controller.check(FaultController.after(StoreOperation.GetManifest))).drain
        )
    )

  override def delete(blob: BinaryKey.Blob): IO[StoreError, Boolean] =
    FaultController.around(controller, StoreOperation.DeleteManifest)(underlying.delete(blob))

  override def healthCheck: IO[StoreError, Unit] =
    FaultController.around(controller, StoreOperation.HealthCheck)(underlying.healthCheck)

/** Logical-store decorator useful for third-party adapters that cannot expose components. */
final class FaultingBlobStore(
  underlying: BlobStore,
  controller: FaultController,
) extends BlobStore:
  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink.unwrap(
      controller
        .check(FaultController.before(StoreOperation.PutBlob))
        .as(underlying.put(plan).mapZIO(result => controller.check(FaultController.after(StoreOperation.PutBlob)).as(result)))
    )

  override def get(key: BinaryKey.Blob): ZStream[Any, StoreError, Byte] =
    FaultController.stream(controller, StoreOperation.GetBlob, underlying.get(key))

  override def getRange(key: BinaryKey.Blob, start: BlobOffset, length: FileSize): ZStream[Any, StoreError, Byte] =
    FaultController.stream(controller, StoreOperation.GetRange, underlying.getRange(key, start, length))

  override def stat(key: BinaryKey.Blob): IO[StoreError, Option[BlobStat]] =
    FaultController.around(controller, StoreOperation.StatBlob)(underlying.stat(key))

  override def inventoryPage(after: Option[InventoryCursor], limit: InventoryPageSize): IO[StoreError, InventoryPage[BlobListing]] =
    FaultController.around(controller, StoreOperation.Inventory)(underlying.inventoryPage(after, limit))

  override def inspect(key: BinaryKey.Blob): IO[StoreError, Option[BlobDescription]] =
    FaultController.around(controller, StoreOperation.InspectBlob)(underlying.inspect(key))

  override def delete(key: BinaryKey.Blob): IO[StoreError, Unit] =
    FaultController.around(controller, StoreOperation.DeleteBlob)(underlying.delete(key))

  override def healthCheck: IO[StoreError, Unit] =
    FaultController.around(controller, StoreOperation.HealthCheck)(underlying.healthCheck)
