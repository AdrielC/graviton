package graviton.runtime.upload

import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.core.types.FileSize
import graviton.runtime.upload.ResumableUploadRepository.Error

import java.time.Instant

/** Pure state machine shared by durable and in-memory repositories. */
private[graviton] final case class ResumableUploadLedger(
  session: ResumableUploadSession,
  parts: Vector[ResumableUploadPart],
  reservations: Map[UploadPartId, UploadPartReservation],
  commitLease: Option[ResumableUploadLedger.CommitLease],
)

private[graviton] object ResumableUploadLedger:
  final case class CommitLease(id: UploadLeaseId, expiresAt: Instant)

  def initial(session: ResumableUploadSession): ResumableUploadLedger =
    ResumableUploadLedger(session, Vector.empty, Map.empty, None)

  def current(ledger: ResumableUploadLedger, now: Instant): Either[Error, ResumableUploadSession] =
    ensureCurrent(ledger.session, now).map(_ => ledger.session)

  def reservePart(
    ledger: ResumableUploadLedger,
    partId: UploadPartId,
    expectedOffset: UploadOffset,
    locator: BlobLocator,
    leaseId: UploadLeaseId,
    now: Instant,
    leaseExpiresAt: Instant,
    maxParts: UploadPartNumber,
  ): Either[Error, (ResumableUploadLedger, UploadPartReservationResult)] =
    for
      _      <- ensureCurrent(ledger.session, now)
      result <- ledger.parts.find(_.id == partId) match
                  case Some(part) => Right(ledger -> UploadPartReservationResult.AlreadyApplied(ledger.session, part))
                  case None       =>
                    val activeReservations = ledger.reservations.valuesIterator.filter(_.leaseExpiresAt.isAfter(now)).toVector
                    activeReservations.find(_.partId == partId).orElse(activeReservations.headOption) match
                      case Some(active) =>
                        Left(Error.PartBusy(partId, active.leaseExpiresAt))
                      case None         =>
                        for
                          _          <- ensureOpen(ledger.session)
                          _          <- Either.cond(
                                          expectedOffset == ledger.session.offset,
                                          (),
                                          Error.OffsetMismatch(expectedOffset, ledger.session.offset),
                                        )
                          _          <- Either.cond(
                                          ledger.session.partCount.value < maxParts.value,
                                          (),
                                          Error.PartLimitExceeded(maxParts),
                                        )
                          reservation = UploadPartReservation(
                                          ledger.session.key,
                                          partId,
                                          ledger.session.partCount,
                                          expectedOffset,
                                          locator,
                                          leaseId,
                                          leaseExpiresAt,
                                        )
                          next        = ledger.copy(reservations = Map(partId -> reservation))
                        yield next -> UploadPartReservationResult.Reserved(reservation)
    yield result

  def completePart(
    ledger: ResumableUploadLedger,
    reservation: UploadPartReservation,
    size: FileSize,
    now: Instant,
  ): Either[Error, (ResumableUploadLedger, ResumableUploadSession)] =
    for
      _       <- ensureCurrent(ledger.session, now)
      _       <- ensureOpen(ledger.session)
      current <- ledger.reservations.get(reservation.partId).toRight(Error.LeaseLost())
      _       <- Either.cond(current.leaseId == reservation.leaseId, (), Error.LeaseLost())
      end     <- try Right(java.lang.Math.addExact(reservation.offset.value, size.value))
                 catch
                   case _: ArithmeticException => Left(Error.SizeExceeded(ledger.session.intent.expectedSize.getOrElse(size), Long.MaxValue))
      _       <- ledger.session.intent.expectedSize match
                   case Some(expected) => Either.cond(end <= expected.value, (), Error.SizeExceeded(expected, end))
                   case None           => Right(())
      offset  <- UploadOffset.either(end).left.map(_ => Error.SizeExceeded(ledger.session.intent.expectedSize.getOrElse(size), end))
      count   <- UploadPartNumber
                   .either(ledger.session.partCount.value + 1)
                   .left
                   .map(_ => Error.PartLimitExceeded(UploadPartNumber.applyUnsafe(65535)))
      part     = ResumableUploadPart(
                   reservation.partId,
                   reservation.number,
                   reservation.offset,
                   size,
                   reservation.locator,
                 )
      updated  = ledger.session.copy(offset = offset, partCount = count)
      next     = ledger.copy(
                   session = updated,
                   parts = ledger.parts :+ part,
                   reservations = ledger.reservations - reservation.partId,
                 )
    yield next -> updated

  def abortPart(
    ledger: ResumableUploadLedger,
    reservation: UploadPartReservation,
  ): ResumableUploadLedger =
    if ledger.reservations.get(reservation.partId).exists(_.leaseId == reservation.leaseId) then
      ledger.copy(reservations = ledger.reservations - reservation.partId)
    else ledger

  def reserveCommit(
    ledger: ResumableUploadLedger,
    leaseId: UploadLeaseId,
    now: Instant,
    leaseExpiresAt: Instant,
  ): Either[Error, (ResumableUploadLedger, UploadCommitReservationResult)] =
    for
      _      <- ensureCurrent(ledger.session, now)
      result <- (ledger.session.phase, ledger.session.committedBlob, ledger.commitLease) match
                  case (ResumableUploadPhase.Committed, Some(blob), _)                                     =>
                    Right(ledger -> UploadCommitReservationResult.AlreadyCommitted(ledger.session, blob))
                  case (ResumableUploadPhase.Committing, _, Some(active)) if active.expiresAt.isAfter(now) =>
                    Left(Error.CommitBusy(active.expiresAt))
                  case (ResumableUploadPhase.Open | ResumableUploadPhase.Committing, _, _)                 =>
                    val updated = ledger.session.copy(phase = ResumableUploadPhase.Committing)
                    val next    = ledger.copy(session = updated, commitLease = Some(CommitLease(leaseId, leaseExpiresAt)))
                    Right(next -> UploadCommitReservationResult.Reserved(updated, leaseId))
                  case _                                                                                   => Left(Error.InvalidState(ledger.session.key, ledger.session.phase))
    yield result

  def completeCommit(
    ledger: ResumableUploadLedger,
    leaseId: UploadLeaseId,
    blob: BinaryKey.Blob,
    now: Instant,
  ): Either[Error, (ResumableUploadLedger, ResumableUploadSession)] =
    for
      _      <- Either.cond(
                  ledger.commitLease.exists(value => value.id == leaseId && value.expiresAt.isAfter(now)),
                  (),
                  Error.LeaseLost(),
                )
      updated = ledger.session.copy(phase = ResumableUploadPhase.Committed, committedBlob = Some(blob))
      next    = ledger.copy(session = updated, commitLease = None)
    yield next -> updated

  def releaseCommit(ledger: ResumableUploadLedger, leaseId: UploadLeaseId): ResumableUploadLedger =
    if ledger.commitLease.exists(_.id == leaseId) then
      ledger.copy(
        session = ledger.session.copy(phase = ResumableUploadPhase.Open),
        commitLease = None,
      )
    else ledger

  def cancel(
    ledger: ResumableUploadLedger,
    now: Instant,
  ): Either[Error, (ResumableUploadLedger, ResumableUploadSession)] =
    for
      _      <- ensureCurrent(ledger.session, now)
      _      <- Either.cond(
                  ledger.session.phase != ResumableUploadPhase.Committed,
                  (),
                  Error.InvalidState(ledger.session.key, ledger.session.phase),
                )
      updated = ledger.session.copy(phase = ResumableUploadPhase.Cancelled)
      next    = ledger.copy(session = updated, reservations = Map.empty, commitLease = None)
    yield next -> updated

  def isExpired(session: ResumableUploadSession, now: Instant): Boolean =
    session.phase != ResumableUploadPhase.Committed && !session.expiresAt.isAfter(now)

  private def ensureCurrent(session: ResumableUploadSession, now: Instant): Either[Error, Unit] =
    Either.cond(!isExpired(session, now), (), Error.Expired(session.key))

  private def ensureOpen(session: ResumableUploadSession): Either[Error, Unit] =
    Either.cond(
      session.phase == ResumableUploadPhase.Open,
      (),
      Error.InvalidState(session.key, session.phase),
    )
