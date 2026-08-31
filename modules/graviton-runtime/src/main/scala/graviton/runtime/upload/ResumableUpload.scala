package graviton.runtime.upload

import graviton.core.RefinedTypeExt
import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.core.types.FileSize
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.stores.{
  MutableObjectStore,
  StoreBackend,
  StoreError,
  StoreOperation,
  TransferBudget,
  TransferFootprint,
  TransferScope,
}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric
import zio.*
import zio.stream.ZStream

import java.time.Instant

/** Client-generated idempotency key for one resumable upload part. */
type UploadPartId = UploadPartId.T
object UploadPartId extends RefinedTypeExt[String, CanonicalUuidConstraint]

/** Server-generated lease identity. It is never accepted from an untrusted caller. */
type UploadLeaseId = UploadLeaseId.T
object UploadLeaseId extends RefinedTypeExt[String, CanonicalUuidConstraint]

type UploadPartNumber = UploadPartNumber.T
object UploadPartNumber extends RefinedTypeExt[Int, numeric.GreaterEqual[0] & numeric.LessEqual[65535]]

type UploadOffset = UploadOffset.T
object UploadOffset extends RefinedTypeExt[Long, numeric.GreaterEqual[0L] & numeric.LessEqual[1099511627776L]]

enum ResumableUploadPhase derives CanEqual:
  case Open
  case Committing
  case Committed
  case Cancelled

final case class ResumableUploadSession(
  key: UploadSessionKey,
  intent: UploadIntent,
  offset: UploadOffset,
  partCount: UploadPartNumber,
  createdAt: Instant,
  expiresAt: Instant,
  phase: ResumableUploadPhase,
  committedBlob: Option[BinaryKey.Blob],
)

final case class ResumableUploadPart(
  id: UploadPartId,
  number: UploadPartNumber,
  offset: UploadOffset,
  size: FileSize,
  locator: BlobLocator,
)

final case class UploadPartReservation(
  key: UploadSessionKey,
  partId: UploadPartId,
  number: UploadPartNumber,
  offset: UploadOffset,
  locator: BlobLocator,
  leaseId: UploadLeaseId,
  leaseExpiresAt: Instant,
)

enum UploadPartReservationResult:
  case Reserved(value: UploadPartReservation)
  case AlreadyApplied(session: ResumableUploadSession, part: ResumableUploadPart)

enum UploadCommitReservationResult:
  case Reserved(session: ResumableUploadSession, leaseId: UploadLeaseId)
  case AlreadyCommitted(session: ResumableUploadSession, blob: BinaryKey.Blob)

/**
 * Durable control-plane state for resumable uploads.
 *
 * Implementations must serialize reserve/complete transitions for one session.
 * Part payloads are stored separately by [[MutableObjectStore]], so this
 * service never accepts or materializes arbitrary bytes.
 */
trait ResumableUploadRepository:
  import ResumableUploadRepository.Error

  def healthCheck: IO[Error, Unit]

  def create(
    key: UploadSessionKey,
    intent: UploadIntent,
    createdAt: Instant,
    expiresAt: Instant,
  ): IO[Error, ResumableUploadSession]

  def get(key: UploadSessionKey, now: Instant): IO[Error, Option[ResumableUploadSession]]

  def reservePart(
    key: UploadSessionKey,
    partId: UploadPartId,
    expectedOffset: UploadOffset,
    locator: BlobLocator,
    leaseId: UploadLeaseId,
    now: Instant,
    leaseExpiresAt: Instant,
    maxParts: UploadPartNumber,
  ): IO[Error, UploadPartReservationResult]

  def completePart(
    reservation: UploadPartReservation,
    size: FileSize,
    now: Instant,
  ): IO[Error, ResumableUploadSession]

  def abortPart(reservation: UploadPartReservation): UIO[Unit]

  def parts(key: UploadSessionKey): ZStream[Any, Error, ResumableUploadPart]

  def reserveCommit(
    key: UploadSessionKey,
    leaseId: UploadLeaseId,
    now: Instant,
    leaseExpiresAt: Instant,
  ): IO[Error, UploadCommitReservationResult]

  def completeCommit(
    key: UploadSessionKey,
    leaseId: UploadLeaseId,
    blob: BinaryKey.Blob,
    now: Instant,
  ): IO[Error, ResumableUploadSession]

  def releaseCommit(key: UploadSessionKey, leaseId: UploadLeaseId): UIO[Unit]

  def cancel(key: UploadSessionKey, now: Instant): IO[Error, ResumableUploadSession]

  def expired(before: Instant): ZStream[Any, Error, UploadSessionKey]

  /** Committed sessions that still retain staging locators after a crash. */
  def cleanupPending: ZStream[Any, Error, UploadSessionKey]

  /** Remove staging locator rows after their objects have been deleted. */
  def clearParts(key: UploadSessionKey): IO[Error, Unit]

  def delete(key: UploadSessionKey): IO[Error, Unit]

object ResumableUploadRepository:
  sealed abstract class Error(message: String, cause: Throwable = null) extends Exception(message, cause)

  object Error:
    final case class AlreadyExists(key: UploadSessionKey)       extends Error(s"resumable upload '${key.entityId}' already exists")
    final case class Missing(key: UploadSessionKey)             extends Error(s"resumable upload '${key.entityId}' does not exist")
    final case class Expired(key: UploadSessionKey)             extends Error(s"resumable upload '${key.entityId}' has expired")
    final case class InvalidState(key: UploadSessionKey, phase: ResumableUploadPhase)
        extends Error(s"resumable upload '${key.entityId}' is ${phase.toString.toLowerCase}")
    final case class OffsetMismatch(expected: UploadOffset, actual: UploadOffset)
        extends Error(s"upload offset mismatch: expected ${expected.value}, current offset is ${actual.value}")
    final case class PartLimitExceeded(limit: UploadPartNumber) extends Error(s"resumable upload exceeds ${limit.value} parts")
    final case class PartBusy(partId: UploadPartId, retryAt: Instant)
        extends Error(s"upload part '${partId.value}' is leased until $retryAt")
    final case class CommitBusy(retryAt: Instant)               extends Error(s"resumable upload commit is leased until $retryAt")
    final case class LeaseLost()                                extends Error("resumable upload lease is no longer current")
    final case class SizeExceeded(expected: FileSize, actual: Long)
        extends Error(s"resumable upload exceeds its declared ${expected.value} bytes: $actual")
    final case class Storage(operation: String, underlying: Throwable)
        extends Error(s"resumable upload repository $operation failed", underlying)

final case class ResumableUploadConfig(
  sessionTtl: Duration = 24.hours,
  partLease: Duration = 15.minutes,
  commitLease: Duration = 30.minutes,
  cleanupInterval: Duration = 15.minutes,
  maxPartBytes: FileSize = FileSize.unsafe(256L * 1024L * 1024L),
  maxParts: UploadPartNumber = UploadPartNumber.applyUnsafe(8192),
):
  require(sessionTtl > Duration.Zero, "sessionTtl must be positive")
  require(partLease > Duration.Zero, "partLease must be positive")
  require(commitLease > Duration.Zero, "commitLease must be positive")
  require(cleanupInterval > Duration.Zero, "cleanupInterval must be positive")
  require(maxParts.value > 0, "maxParts must be positive")

object ResumableUploadConfig:
  val Default: ResumableUploadConfig = ResumableUploadConfig()

  val config: Config[ResumableUploadConfig] =
    (Config.duration("session-ttl").withDefault(Default.sessionTtl) ++
      Config.duration("part-lease").withDefault(Default.partLease) ++
      Config.duration("commit-lease").withDefault(Default.commitLease) ++
      Config.duration("cleanup-interval").withDefault(Default.cleanupInterval) ++
      Config.long("max-part-bytes").withDefault(Default.maxPartBytes.value) ++
      Config.int("max-parts").withDefault(Default.maxParts.value))
      .mapOrFail { case (sessionTtl, partLease, commitLease, cleanupInterval, maxPartBytes, maxParts) =>
        (for
          bytes <- FileSize.either(maxPartBytes)
          parts <- UploadPartNumber.either(maxParts).flatMap(value => Either.cond(value.value > 0, value, "max-parts must be positive"))
          _     <- Either.cond(sessionTtl > Duration.Zero, (), "session-ttl must be positive")
          _     <- Either.cond(partLease > Duration.Zero, (), "part-lease must be positive")
          _     <- Either.cond(commitLease > Duration.Zero, (), "commit-lease must be positive")
          _     <- Either.cond(cleanupInterval > Duration.Zero, (), "cleanup-interval must be positive")
        yield ResumableUploadConfig(sessionTtl, partLease, commitLease, cleanupInterval, bytes, parts)).left
          .map(message => Config.Error.InvalidData(Chunk.empty, message))
      }
      .nested("resumable-uploads")

  val layer: ZLayer[Any, Config.Error, ResumableUploadConfig] =
    ZLayer.fromZIO(ZIO.config(config))

/** Validated staging namespace. Locators are opaque to upload callers. */
final case class UploadStagingTarget private (
  scheme: String,
  bucket: String,
  prefix: String,
):
  private[upload] val healthLocator: BlobLocator =
    BlobLocator
      .from(scheme, bucket, s"$prefix/.health")
      .fold(message => throw new IllegalStateException(message), identity)

  def locator(key: UploadSessionKey, partId: UploadPartId, leaseId: UploadLeaseId): BlobLocator =
    BlobLocator
      .from(
        scheme,
        bucket,
        s"$prefix/${key.tenantId.value}/${key.uploadSessionId.value}/${partId.value}-${leaseId.value}.part",
      )
      .fold(message => throw new IllegalStateException(message), identity)

object UploadStagingTarget:
  def from(scheme: String, bucket: String, prefix: String = "resumable"): Either[String, UploadStagingTarget] =
    val normalized = prefix.trim.stripPrefix("/").stripSuffix("/")
    BlobLocator.from(scheme, bucket, s"$normalized/probe").map(_ => UploadStagingTarget(scheme, bucket, normalized))

/** Provider-neutral admission boundary for one streamed staging part. */
trait ResumablePartAdmission:
  def reserveScoped(key: UploadSessionKey): ZIO[Scope, StoreError, Unit]

object ResumablePartAdmission:
  val disabled: ResumablePartAdmission = new ResumablePartAdmission:
    override def reserveScoped(key: UploadSessionKey): ZIO[Scope, StoreError, Unit] =
      val _ = key
      ZIO.unit

  def transferBudget(
    budget: TransferBudget,
    backend: StoreBackend,
    footprint: TransferFootprint,
  ): ResumablePartAdmission = new ResumablePartAdmission:
    override def reserveScoped(key: UploadSessionKey): ZIO[Scope, StoreError, Unit] =
      budget.reserveScoped(
        TransferScope(Some(key.tenantId), backend),
        StoreOperation.PutBlob,
        footprint,
      )

/** Streaming resumable-upload orchestration independent of HTTP and storage vendors. */
final class ResumableUploadService(
  repository: ResumableUploadRepository,
  staging: MutableObjectStore,
  target: UploadStagingTarget,
  config: ResumableUploadConfig = ResumableUploadConfig.Default,
  metrics: MetricsRegistry = MetricsRegistry.noop,
  partAdmission: ResumablePartAdmission = ResumablePartAdmission.disabled,
):
  /** Binary-compatible constructor retained for clients compiled against 0.6.x. */
  def this(
    repository: ResumableUploadRepository,
    staging: MutableObjectStore,
    target: UploadStagingTarget,
    config: ResumableUploadConfig,
    metrics: MetricsRegistry,
  ) = this(repository, staging, target, config, metrics, ResumablePartAdmission.disabled)

  import ResumableUploadRepository.Error as RepositoryError
  import ResumableUploadService.*

  /** Verify the durable ledger and staging target used by resumable routes. */
  def healthCheck: IO[Error, Unit] =
    repository.healthCheck.mapError(Error.Repository.apply) *>
      staging
        .head(target.healthLocator)
        .unit
        .mapError(Error.Staging("health check", _))

  def create(key: UploadSessionKey, intent: UploadIntent): IO[Error, ResumableUploadSession] =
    for
      now       <- Clock.instant
      expiresAt <- addDuration(now, config.sessionTtl)
      session   <- repository.create(key, intent, now, expiresAt).mapError(Error.Repository.apply)
      _         <- metrics.counter(MetricKeys.ResumableUploadsCreatedTotal, Map.empty)
    yield session

  def status(key: UploadSessionKey): IO[Error, ResumableUploadSession] =
    Clock.instant.flatMap(now => repository.get(key, now).mapError(Error.Repository.apply).someOrFail(Error.NotFound(key)))

  /**
   * Persist one retryable part. The stream is consumed exactly once and is
   * rejected as soon as it crosses the configured logical bound.
   */
  def append(
    key: UploadSessionKey,
    partId: UploadPartId,
    expectedOffset: UploadOffset,
    expectedPartSize: Option[FileSize],
    bytes: ZStream[Any, Throwable, Byte],
  ): IO[Error, AppendResult] =
    appendSource(key, partId, expectedOffset, expectedPartSize, UploadSource.fromThrowable(bytes))

  def appendSource(
    key: UploadSessionKey,
    partId: UploadPartId,
    expectedOffset: UploadOffset,
    expectedPartSize: Option[FileSize],
    source: UploadSource,
  ): IO[Error, AppendResult] =
    ZIO.uninterruptibleMask { restore =>
      for
        now          <- Clock.instant
        leaseId      <- randomLeaseId
        leaseExpires <- addDuration(now, config.partLease)
        locator       = target.locator(key, partId, leaseId)
        reserved     <- repository
                          .reservePart(
                            key,
                            partId,
                            expectedOffset,
                            locator,
                            leaseId,
                            now,
                            leaseExpires,
                            config.maxParts,
                          )
                          .mapError(Error.Repository.apply)
        result       <- reserved match
                          case UploadPartReservationResult.AlreadyApplied(session, part) =>
                            metrics
                              .counter(MetricKeys.ResumablePartRetriesTotal, Map("outcome" -> "already_applied"))
                              .as(AppendResult(session, part, replayed = true))
                          case UploadPartReservationResult.Reserved(reservation)         =>
                            val streamed =
                              UploadByteStream.enforceExpectedSizeTyped(
                                UploadByteStream.enforceMaximumSizeTyped(source.bytes, config.maxPartBytes),
                                expectedPartSize,
                              )
                            restore(
                              ZIO.scoped(
                                partAdmission.reserveScoped(key).mapError(Error.Admission.apply) *>
                                  writePart(reservation, streamed)
                              )
                            )
                              .onExit {
                                case Exit.Success(_) => ZIO.unit
                                case _               =>
                                  staging.delete(reservation.locator).ignore *>
                                    repository.abortPart(reservation)
                              }
      yield result
    }

  /** Lazily reconstruct the staged bytes in committed part order. */
  def bytes(key: UploadSessionKey): ZStream[Any, Error, Byte] =
    repository
      .parts(key)
      .mapError(Error.Repository.apply)
      .flatMap { part =>
        UploadByteStream
          .enforceExpectedSize(
            staging.get(part.locator).mapError(error => Error.Staging("read", error)),
            Some(part.size),
          )
          .mapError {
            case value: Error                  => value
            case value: UploadByteStream.Error => Error.InvalidPart(value)
            case value                         => Error.Staging("read", value)
          }
      }

  /**
   * Acquire an expiring commit lease and finalize the staged stream exactly
   * once at the session boundary. Re-running finalization is safe because the
   * destination is content-addressed.
   */
  def commit(
    key: UploadSessionKey
  )(
    finalize: (UploadIntent, ZStream[Any, Throwable, Byte]) => IO[Throwable, BinaryKey.Blob]
  ): IO[Error, CommitResult] =
    commitSource(key)((intent, source) => finalize(intent, source.bytes.mapError(value => value: Throwable)))
      .mapError {
        case value: Error     => value
        case value: Throwable => Error.Finalization(value)
      }

  def commitSource[E](
    key: UploadSessionKey
  )(
    finalize: (UploadIntent, UploadSource) => IO[E, BinaryKey.Blob]
  ): IO[Error | E, CommitResult] =
    ZIO.uninterruptibleMask { restore =>
      for
        now          <- Clock.instant
        leaseId      <- randomLeaseId
        leaseExpires <- addDuration(now, config.commitLease)
        reserved     <- repository
                          .reserveCommit(key, leaseId, now, leaseExpires)
                          .mapError(Error.Repository.apply)
        result       <- reserved match
                          case UploadCommitReservationResult.AlreadyCommitted(session, blob) =>
                            ZIO.succeed(CommitResult(session, blob, replayed = true))
                          case UploadCommitReservationResult.Reserved(session, currentLease) =>
                            val complete =
                              for
                                _      <- validateCompleteSize(session)
                                blob   <- finalize(
                                            session.intent,
                                            UploadSource.typed(
                                              bytes(key).mapError(error => UploadSourceError.Rejected(error.getMessage, error))
                                            ),
                                          )
                                stored <- repository
                                            .completeCommit(key, currentLease, blob, now)
                                            .mapError(Error.Repository.apply)
                                _      <- cleanupCommitted(key)
                                            .catchAll(error => ZIO.logWarningCause("Resumable staging cleanup deferred", Cause.fail(error)))
                                _      <- metrics.counter(MetricKeys.ResumableUploadsCommittedTotal, Map.empty)
                              yield CommitResult(stored, blob, replayed = false)
                            restore(complete).onExit {
                              case Exit.Success(_) => ZIO.unit
                              case _               => repository.releaseCommit(key, currentLease)
                            }
      yield result
    }

  def cancel(key: UploadSessionKey): IO[Error, Unit] =
    for
      now <- Clock.instant
      _   <- repository.cancel(key, now).mapError(Error.Repository.apply)
      _   <- cleanupParts(key)
      _   <- repository.delete(key).mapError(Error.Repository.apply)
      _   <- metrics.counter(MetricKeys.ResumableUploadsCancelledTotal, Map.empty)
    yield ()

  /** Delete expired session objects and ledger rows without retaining inventory. */
  def cleanupExpired: IO[Error, Long] =
    for
      now   <- Clock.instant
      _     <- repository.cleanupPending
                 .mapZIO(cleanupCommitted)
                 .runDrain
                 .mapError {
                   case value: Error           => value
                   case value: RepositoryError => Error.Repository(value)
                 }
      count <- repository
                 .expired(now)
                 .mapZIO(key => cleanupParts(key) *> repository.delete(key).mapError(Error.Repository.apply).as(1L))
                 .runSum
                 .mapError {
                   case value: Error           => value
                   case value: RepositoryError => Error.Repository(value)
                 }
      _     <- metrics.counterBy(MetricKeys.ResumableUploadsExpiredTotal, count, Map.empty)
    yield count

  private def writePart(
    reservation: UploadPartReservation,
    bytes: ZStream[Any, UploadSourceError | UploadByteStream.Error, Byte],
  ): IO[Error, AppendResult] =
    for
      observed <- Ref.make(0L)
      counted   = bytes.mapChunksZIO { chunk =>
                    observed.modify { current =>
                      if current > Long.MaxValue - chunk.length.toLong then
                        Left(Error.InvalidPart(UploadByteStream.Error.ByteCountOverflow())) -> current
                      else Right(chunk)                                                     -> (current + chunk.length.toLong)
                    }.absolve
                  }
      _        <- counted
                    .run(staging.put(reservation.locator))
                    .mapError {
                      case value: UploadByteStream.Error => Error.InvalidPart(value)
                      case value: UploadSourceError      => Error.Source(value)
                      case value                         => Error.Staging("write", value)
                    }
      size     <- observed.get
      refined  <- ZIO
                    .fromEither(FileSize.either(size))
                    .mapError(message => Error.EmptyOrInvalidPart(message))
      now      <- Clock.instant
      session  <- repository.completePart(reservation, refined, now).mapError(Error.Repository.apply)
      part      = ResumableUploadPart(reservation.partId, reservation.number, reservation.offset, refined, reservation.locator)
      _        <- metrics.counterBy(MetricKeys.ResumableBytesStagedTotal, refined.value, Map.empty)
      _        <- metrics.counter(MetricKeys.ResumablePartsCompletedTotal, Map.empty)
    yield AppendResult(session, part, replayed = false)

  private def cleanupParts(key: UploadSessionKey): IO[Error, Unit] =
    repository
      .parts(key)
      .mapZIO(part => staging.delete(part.locator).mapError(error => Error.Staging("delete", error)))
      .runDrain
      .mapError {
        case value: Error           => value
        case value: RepositoryError => Error.Repository(value)
      }

  private def cleanupCommitted(key: UploadSessionKey): IO[Error, Unit] =
    cleanupParts(key) *> repository.clearParts(key).mapError(Error.Repository.apply)

  private def validateCompleteSize(session: ResumableUploadSession): IO[Error, Unit] =
    session.intent.expectedSize match
      case None           => ZIO.unit
      case Some(expected) =>
        ZIO
          .fail(Error.Incomplete(expected, session.offset))
          .unless(expected.value == session.offset.value)
          .unit

  private def randomLeaseId: UIO[UploadLeaseId] =
    Random.nextUUID.map(value => UploadLeaseId.applyUnsafe(value.toString))

  private def addDuration(instant: Instant, duration: Duration): IO[Error, Instant] =
    ZIO
      .attempt(instant.plusNanos(duration.toNanos))
      .mapError(error => Error.ClockOverflow(error))

object ResumableUploadService:
  final case class AppendResult(
    session: ResumableUploadSession,
    part: ResumableUploadPart,
    replayed: Boolean,
  )

  final case class CommitResult(
    session: ResumableUploadSession,
    blob: BinaryKey.Blob,
    replayed: Boolean,
  )

  sealed abstract class Error(message: String, cause: Throwable = null) extends Exception(message, cause)

  object Error:
    final case class NotFound(key: UploadSessionKey)                         extends Error(s"resumable upload '${key.entityId}' does not exist")
    final case class Repository(underlying: ResumableUploadRepository.Error) extends Error(underlying.getMessage, underlying)
    final case class Staging(operation: String, underlying: Throwable)
        extends Error(s"resumable upload staging $operation failed", underlying)
    final case class Admission(underlying: StoreError)                       extends Error("resumable upload admission failed", underlying)
    final case class Source(underlying: UploadSourceError)                   extends Error(underlying.getMessage, underlying)
    final case class InvalidPart(underlying: UploadByteStream.Error)         extends Error(underlying.getMessage, underlying)
    final case class EmptyOrInvalidPart(detail: String)                      extends Error(s"resumable upload part is invalid: $detail")
    final case class Incomplete(expected: FileSize, actual: UploadOffset)
        extends Error(s"resumable upload is incomplete: expected ${expected.value} bytes, received ${actual.value}")
    final case class Finalization(underlying: Throwable)                     extends Error("resumable upload finalization failed", underlying)
    final case class ClockOverflow(underlying: Throwable)                    extends Error("resumable upload lease time overflow", underlying)
