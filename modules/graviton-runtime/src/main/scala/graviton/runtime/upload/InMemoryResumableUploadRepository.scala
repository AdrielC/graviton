package graviton.runtime.upload

import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.core.types.FileSize
import graviton.runtime.upload.ResumableUploadRepository.Error
import zio.*
import zio.stream.ZStream

import java.time.Instant

/** Deterministic reference implementation used by tests and embedded callers. */
final class InMemoryResumableUploadRepository private (
  state: Ref.Synchronized[Map[UploadSessionKey, ResumableUploadLedger]]
) extends ResumableUploadRepository:

  override val healthCheck: UIO[Unit] = ZIO.unit

  override def create(
    key: UploadSessionKey,
    intent: UploadIntent,
    createdAt: Instant,
    expiresAt: Instant,
  ): IO[Error, ResumableUploadSession] =
    state.modifyZIO { sessions =>
      sessions.get(key) match
        case Some(_) => ZIO.fail(Error.AlreadyExists(key))
        case None    =>
          val session = ResumableUploadSession(
            key,
            intent,
            UploadOffset.applyUnsafe(0L),
            UploadPartNumber.applyUnsafe(0),
            createdAt,
            expiresAt,
            ResumableUploadPhase.Open,
            None,
          )
          ZIO.succeed(session -> sessions.updated(key, ResumableUploadLedger.initial(session)))
    }

  override def get(key: UploadSessionKey, now: Instant): IO[Error, Option[ResumableUploadSession]] =
    state.get.flatMap { sessions =>
      sessions.get(key) match
        case None         => ZIO.none
        case Some(ledger) => ZIO.fromEither(ResumableUploadLedger.current(ledger, now)).asSome
    }

  override def reservePart(
    key: UploadSessionKey,
    partId: UploadPartId,
    expectedOffset: UploadOffset,
    locator: BlobLocator,
    leaseId: UploadLeaseId,
    now: Instant,
    leaseExpiresAt: Instant,
    maxParts: UploadPartNumber,
  ): IO[Error, UploadPartReservationResult] =
    modify(key)(ResumableUploadLedger.reservePart(_, partId, expectedOffset, locator, leaseId, now, leaseExpiresAt, maxParts))

  override def completePart(
    reservation: UploadPartReservation,
    size: FileSize,
    now: Instant,
  ): IO[Error, ResumableUploadSession] =
    modify(reservation.key)(ResumableUploadLedger.completePart(_, reservation, size, now))

  override def abortPart(reservation: UploadPartReservation): UIO[Unit] =
    update(reservation.key)(ResumableUploadLedger.abortPart(_, reservation))

  override def parts(key: UploadSessionKey): ZStream[Any, Error, ResumableUploadPart] =
    ZStream.unwrap {
      state.get.flatMap { sessions =>
        sessions.get(key) match
          case None         => ZIO.fail(Error.Missing(key))
          case Some(ledger) => ZIO.succeed(ZStream.fromIterable(ledger.parts))
      }
    }

  override def reserveCommit(
    key: UploadSessionKey,
    leaseId: UploadLeaseId,
    now: Instant,
    leaseExpiresAt: Instant,
  ): IO[Error, UploadCommitReservationResult] =
    modify(key)(ResumableUploadLedger.reserveCommit(_, leaseId, now, leaseExpiresAt))

  override def completeCommit(
    key: UploadSessionKey,
    leaseId: UploadLeaseId,
    blob: BinaryKey.Blob,
    now: Instant,
  ): IO[Error, ResumableUploadSession] =
    modify(key)(ResumableUploadLedger.completeCommit(_, leaseId, blob, now))

  override def releaseCommit(key: UploadSessionKey, leaseId: UploadLeaseId): UIO[Unit] =
    update(key)(ResumableUploadLedger.releaseCommit(_, leaseId))

  override def cancel(key: UploadSessionKey, now: Instant): IO[Error, ResumableUploadSession] =
    modify(key)(ResumableUploadLedger.cancel(_, now))

  override def expired(before: Instant): ZStream[Any, Error, UploadSessionKey] =
    ZStream.fromZIO(state.get).flatMap { sessions =>
      ZStream.fromIterable(
        sessions.valuesIterator
          .map(_.session)
          .filter(ResumableUploadLedger.isExpired(_, before))
          .map(_.key)
          .toList
      )
    }

  override def cleanupPending: ZStream[Any, Error, UploadSessionKey] =
    ZStream.fromZIO(state.get).flatMap { sessions =>
      ZStream.fromIterable(
        sessions.valuesIterator
          .filter(ledger => ledger.session.phase == ResumableUploadPhase.Committed && ledger.parts.nonEmpty)
          .map(_.session.key)
          .toList
      )
    }

  override def clearParts(key: UploadSessionKey): IO[Error, Unit] =
    modify(key)(ledger => Right(ledger.copy(parts = Vector.empty) -> ()))

  override def delete(key: UploadSessionKey): UIO[Unit] =
    state.update(_ - key)

  private def modify[A](
    key: UploadSessionKey
  )(
    operation: ResumableUploadLedger => Either[Error, (ResumableUploadLedger, A)]
  ): IO[Error, A] =
    state.modifyZIO { sessions =>
      sessions.get(key) match
        case None         => ZIO.fail(Error.Missing(key))
        case Some(ledger) =>
          ZIO.fromEither(operation(ledger)).map { case (next, value) => value -> sessions.updated(key, next) }
    }

  private def update(key: UploadSessionKey)(operation: ResumableUploadLedger => ResumableUploadLedger): UIO[Unit] =
    state.update(sessions => sessions.updatedWith(key)(_.map(operation)))

object InMemoryResumableUploadRepository:
  def make: UIO[InMemoryResumableUploadRepository] =
    Ref.Synchronized
      .make(Map.empty[UploadSessionKey, ResumableUploadLedger])
      .map(new InMemoryResumableUploadRepository(_))

  val layer: ULayer[ResumableUploadRepository] =
    ZLayer.fromZIO(make)
