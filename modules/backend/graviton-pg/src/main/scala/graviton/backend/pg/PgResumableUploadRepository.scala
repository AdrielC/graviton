package graviton.backend.pg

import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.locator.BlobLocator
import graviton.core.types.FileSize
import graviton.runtime.upload.*
import graviton.runtime.upload.ResumableUploadRepository.Error
import graviton.shared.MediaTypeText
import zio.*
import zio.stream.ZStream

import java.net.URI
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Timestamp}
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** PostgreSQL row-locked resumable session ledger for shared-node deployments. */
final class PgResumableUploadRepository(dataSource: DataSource) extends ResumableUploadRepository:
  import PgResumableUploadRepository.*

  override def healthCheck: IO[Error, Unit] =
    blocking("health check") {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement("SELECT 1 FROM graviton.upload_session LIMIT 1")
        try
          val result = statement.executeQuery()
          try ()
          finally result.close()
        finally statement.close()
      finally connection.close()
    }

  override def create(
    key: UploadSessionKey,
    intent: UploadIntent,
    createdAt: Instant,
    expiresAt: Instant,
  ): IO[Error, ResumableUploadSession] =
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
    blocking("create") {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement(
          """INSERT INTO graviton.upload_session
            |  (tenant_id, upload_session_id, content_type, expected_size, created_at, expires_at)
            |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin
        )
        try
          bindKey(statement, key)
          statement.setString(3, intent.contentType.fullType)
          intent.expectedSize match
            case Some(size) => statement.setLong(4, size.value)
            case None       => statement.setNull(4, java.sql.Types.BIGINT)
          statement.setTimestamp(5, Timestamp.from(createdAt))
          statement.setTimestamp(6, Timestamp.from(expiresAt))
          statement.executeUpdate()
          session
        catch case error: SQLException if error.getSQLState == UniqueViolation => throw DomainFailure(Error.AlreadyExists(key))
        finally statement.close()
      finally connection.close()
    }

  override def get(key: UploadSessionKey, now: Instant): IO[Error, Option[ResumableUploadSession]] =
    blocking("get") {
      val connection = dataSource.getConnection()
      try
        selectSession(connection, key, forUpdate = false) match
          case None      => None
          case Some(row) =>
            ensureCurrent(row.session, now)
            Some(row.session)
      finally connection.close()
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
    transaction("reserve part") { connection =>
      val row = requiredSession(connection, key)
      ensureCurrent(row.session, now)
      selectPart(connection, key, partId) match
        case Some(PartRow.Completed(part))                                      => UploadPartReservationResult.AlreadyApplied(row.session, part)
        case Some(PartRow.Reserved(value)) if value.leaseExpiresAt.isAfter(now) =>
          throw DomainFailure(Error.PartBusy(partId, value.leaseExpiresAt))
        case _                                                                  =>
          deleteExpiredReservations(connection, key, now)
          selectActiveReservation(connection, key, now).foreach(active =>
            throw DomainFailure(Error.PartBusy(partId, active.leaseExpiresAt))
          )
          ensureOpen(row.session)
          if expectedOffset != row.session.offset then throw DomainFailure(Error.OffsetMismatch(expectedOffset, row.session.offset))
          if row.session.partCount.value >= maxParts.value then throw DomainFailure(Error.PartLimitExceeded(maxParts))
          val reservation = UploadPartReservation(
            key,
            partId,
            row.session.partCount,
            expectedOffset,
            locator,
            leaseId,
            leaseExpiresAt,
          )
          insertReservation(connection, reservation, now)
          UploadPartReservationResult.Reserved(reservation)
    }

  override def completePart(
    reservation: UploadPartReservation,
    size: FileSize,
    now: Instant,
  ): IO[Error, ResumableUploadSession] =
    transaction("complete part") { connection =>
      val row          = requiredSession(connection, reservation.key)
      val current      = selectPart(connection, reservation.key, reservation.partId) match
        case Some(PartRow.Reserved(value)) => value
        case _                             => throw DomainFailure(Error.LeaseLost())
      val ledger       = ResumableUploadLedger(
        row.session,
        Vector.empty,
        Map(current.partId -> current),
        row.commitLease,
      )
      val (_, updated) = ResumableUploadLedger
        .completePart(ledger, reservation, size, now)
        .fold(error => throw DomainFailure(error), identity)
      completePartRow(connection, reservation, size, now)
      updateProgress(connection, updated)
      updated
    }

  override def abortPart(reservation: UploadPartReservation): UIO[Unit] =
    blocking("abort part") {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement(
          """DELETE FROM graviton.upload_part
            |WHERE tenant_id = ? AND upload_session_id = ? AND part_id = ?
            |  AND byte_length IS NULL AND lease_id = ?""".stripMargin
        )
        try
          bindKey(statement, reservation.key)
          statement.setObject(3, UUID.fromString(reservation.partId.value))
          statement.setObject(4, UUID.fromString(reservation.leaseId.value))
          statement.executeUpdate()
          ()
        finally statement.close()
      finally connection.close()
    }.catchAll(error => ZIO.logErrorCause(error.getMessage, Cause.fail(error)))

  override def parts(key: UploadSessionKey): ZStream[Any, Error, ResumableUploadPart] =
    ZStream.paginateChunkZIO(0) { nextPart =>
      blocking("list parts") {
        val connection = dataSource.getConnection()
        try
          val statement = connection.prepareStatement(
            """SELECT part_id::text, part_number, byte_offset, byte_length, locator
              |FROM graviton.upload_part
              |WHERE tenant_id = ? AND upload_session_id = ?
              |  AND byte_length IS NOT NULL AND part_number >= ?
              |ORDER BY part_number
              |LIMIT ?""".stripMargin
          )
          try
            bindKey(statement, key)
            statement.setInt(3, nextPart)
            statement.setInt(4, PageSize)
            val result = statement.executeQuery()
            try
              val builder = ChunkBuilder.make[ResumableUploadPart]()
              var last    = nextPart - 1
              while result.next() do
                val part = completedPart(result)
                builder += part
                last = part.number.value
              val chunk   = builder.result()
              chunk -> Option.when(chunk.length == PageSize)(last + 1)
            finally result.close()
          finally statement.close()
        finally connection.close()
      }
    }

  override def reserveCommit(
    key: UploadSessionKey,
    leaseId: UploadLeaseId,
    now: Instant,
    leaseExpiresAt: Instant,
  ): IO[Error, UploadCommitReservationResult] =
    transaction("reserve commit") { connection =>
      val row = requiredSession(connection, key)
      ensureCurrent(row.session, now)
      (row.session.phase, row.session.committedBlob, row.commitLease) match
        case (ResumableUploadPhase.Committed, Some(blob), _)                                     =>
          UploadCommitReservationResult.AlreadyCommitted(row.session, blob)
        case (ResumableUploadPhase.Committing, _, Some(active)) if active.expiresAt.isAfter(now) =>
          throw DomainFailure(Error.CommitBusy(active.expiresAt))
        case (ResumableUploadPhase.Open | ResumableUploadPhase.Committing, _, _)                 =>
          val statement = connection.prepareStatement(
            """UPDATE graviton.upload_session
              |SET phase = 'Committing', commit_lease_id = ?, commit_lease_expires_at = ?, updated_at = clock_timestamp()
              |WHERE tenant_id = ? AND upload_session_id = ?""".stripMargin
          )
          try
            statement.setObject(1, UUID.fromString(leaseId.value))
            statement.setTimestamp(2, Timestamp.from(leaseExpiresAt))
            bindKey(statement, key, start = 3)
            statement.executeUpdate()
          finally statement.close()
          UploadCommitReservationResult.Reserved(row.session.copy(phase = ResumableUploadPhase.Committing), leaseId)
        case _                                                                                   => throw DomainFailure(Error.InvalidState(key, row.session.phase))
    }

  override def completeCommit(
    key: UploadSessionKey,
    leaseId: UploadLeaseId,
    blob: BinaryKey.Blob,
    now: Instant,
  ): IO[Error, ResumableUploadSession] =
    transaction("complete commit") { connection =>
      val row       = requiredSession(connection, key)
      if !row.commitLease.exists(value => value.id == leaseId && value.expiresAt.isAfter(now)) then throw DomainFailure(Error.LeaseLost())
      val statement = connection.prepareStatement(
        """UPDATE graviton.upload_session
          |SET phase = 'Committed', committed_blob = ?, commit_lease_id = NULL,
          |    commit_lease_expires_at = NULL, updated_at = clock_timestamp()
          |WHERE tenant_id = ? AND upload_session_id = ?""".stripMargin
      )
      try
        statement.setString(1, blob.bits.render)
        bindKey(statement, key, start = 2)
        statement.executeUpdate()
      finally statement.close()
      row.session.copy(phase = ResumableUploadPhase.Committed, committedBlob = Some(blob))
    }

  override def releaseCommit(key: UploadSessionKey, leaseId: UploadLeaseId): UIO[Unit] =
    blocking("release commit") {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement(
          """UPDATE graviton.upload_session
            |SET phase = 'Open', commit_lease_id = NULL, commit_lease_expires_at = NULL, updated_at = clock_timestamp()
            |WHERE tenant_id = ? AND upload_session_id = ? AND phase = 'Committing' AND commit_lease_id = ?""".stripMargin
        )
        try
          bindKey(statement, key)
          statement.setObject(3, UUID.fromString(leaseId.value))
          statement.executeUpdate()
          ()
        finally statement.close()
      finally connection.close()
    }.catchAll(error => ZIO.logErrorCause(error.getMessage, Cause.fail(error)))

  override def cancel(key: UploadSessionKey, now: Instant): IO[Error, ResumableUploadSession] =
    transaction("cancel") { connection =>
      val row       = requiredSession(connection, key)
      ensureCurrent(row.session, now)
      if row.session.phase == ResumableUploadPhase.Committed then throw DomainFailure(Error.InvalidState(key, row.session.phase))
      val statement = connection.prepareStatement(
        """UPDATE graviton.upload_session
          |SET phase = 'Cancelled', commit_lease_id = NULL, commit_lease_expires_at = NULL, updated_at = clock_timestamp()
          |WHERE tenant_id = ? AND upload_session_id = ?""".stripMargin
      )
      try
        bindKey(statement, key)
        statement.executeUpdate()
      finally statement.close()
      row.session.copy(phase = ResumableUploadPhase.Cancelled)
    }

  override def expired(before: Instant): ZStream[Any, Error, UploadSessionKey] =
    ZStream.paginateChunkZIO(Option.empty[(String, String)]) { cursor =>
      blocking("list expired") {
        val connection = dataSource.getConnection()
        try
          val statement = connection.prepareStatement(
            """SELECT tenant_id::text, upload_session_id::text
              |FROM graviton.upload_session
              |WHERE phase <> 'Committed' AND expires_at <= ?
              |  AND (? IS NULL OR (tenant_id::text, upload_session_id::text) > (?, ?))
              |ORDER BY tenant_id::text, upload_session_id::text
              |LIMIT ?""".stripMargin
          )
          try
            statement.setTimestamp(1, Timestamp.from(before))
            cursor match
              case None                    =>
                statement.setNull(2, java.sql.Types.VARCHAR)
                statement.setNull(3, java.sql.Types.VARCHAR)
                statement.setNull(4, java.sql.Types.VARCHAR)
              case Some((tenant, session)) =>
                statement.setString(2, tenant)
                statement.setString(3, tenant)
                statement.setString(4, session)
            statement.setInt(5, PageSize)
            val result = statement.executeQuery()
            try
              val builder = ChunkBuilder.make[UploadSessionKey]()
              var last    = Option.empty[(String, String)]
              while result.next() do
                val tenant  = result.getString(1)
                val session = result.getString(2)
                builder += UploadSessionKey(TenantId.applyUnsafe(tenant), UploadSessionId.applyUnsafe(session))
                last = Some(tenant -> session)
              val chunk   = builder.result()
              chunk -> Option.when(chunk.length == PageSize)(last)
            finally result.close()
          finally statement.close()
        finally connection.close()
      }
    }

  override def cleanupPending: ZStream[Any, Error, UploadSessionKey] =
    ZStream.paginateChunkZIO(Option.empty[(String, String)]) { cursor =>
      blocking("list cleanup pending") {
        val connection = dataSource.getConnection()
        try
          val statement = connection.prepareStatement(
            """SELECT session.tenant_id::text, session.upload_session_id::text
              |FROM graviton.upload_session session
              |WHERE session.phase = 'Committed'
              |  AND EXISTS (
              |    SELECT 1 FROM graviton.upload_part part
              |    WHERE part.tenant_id = session.tenant_id
              |      AND part.upload_session_id = session.upload_session_id
              |      AND part.byte_length IS NOT NULL
              |  )
              |  AND (? IS NULL OR (session.tenant_id::text, session.upload_session_id::text) > (?, ?))
              |ORDER BY session.tenant_id::text, session.upload_session_id::text
              |LIMIT ?""".stripMargin
          )
          try
            cursor match
              case None                    =>
                statement.setNull(1, java.sql.Types.VARCHAR)
                statement.setNull(2, java.sql.Types.VARCHAR)
                statement.setNull(3, java.sql.Types.VARCHAR)
              case Some((tenant, session)) =>
                statement.setString(1, tenant)
                statement.setString(2, tenant)
                statement.setString(3, session)
            statement.setInt(4, PageSize)
            val result = statement.executeQuery()
            try
              val builder = ChunkBuilder.make[UploadSessionKey]()
              var last    = Option.empty[(String, String)]
              while result.next() do
                val tenant  = result.getString(1)
                val session = result.getString(2)
                builder += UploadSessionKey(TenantId.applyUnsafe(tenant), UploadSessionId.applyUnsafe(session))
                last = Some(tenant -> session)
              val chunk   = builder.result()
              chunk -> Option.when(chunk.length == PageSize)(last)
            finally result.close()
          finally statement.close()
        finally connection.close()
      }
    }

  override def clearParts(key: UploadSessionKey): IO[Error, Unit] =
    blocking("clear parts") {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement(
          """DELETE FROM graviton.upload_part part
            |USING graviton.upload_session session
            |WHERE part.tenant_id = session.tenant_id
            |  AND part.upload_session_id = session.upload_session_id
            |  AND session.phase = 'Committed'
            |  AND part.tenant_id = ? AND part.upload_session_id = ?""".stripMargin
        )
        try
          bindKey(statement, key)
          statement.executeUpdate()
          ()
        finally statement.close()
      finally connection.close()
    }

  override def delete(key: UploadSessionKey): IO[Error, Unit] =
    blocking("delete") {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement(
          "DELETE FROM graviton.upload_session WHERE tenant_id = ? AND upload_session_id = ?"
        )
        try
          bindKey(statement, key)
          statement.executeUpdate()
          ()
        finally statement.close()
      finally connection.close()
    }

  private def transaction[A](operation: String)(effect: Connection => A): IO[Error, A] =
    blocking(operation) {
      val connection = dataSource.getConnection()
      connection.setAutoCommit(false)
      try
        val value = effect(connection)
        connection.commit()
        value
      catch
        case error: Throwable =>
          try connection.rollback()
          catch case rollback: Throwable => error.addSuppressed(rollback)
          throw error
      finally
        try connection.setAutoCommit(true)
        catch case _: Throwable => ()
        connection.close()
    }

  private def blocking[A](operation: String)(effect: => A): IO[Error, A] =
    ZIO.attemptBlocking(effect).mapError {
      case DomainFailure(error) => error
      case error                => Error.Storage(operation, error)
    }

  private def requiredSession(connection: Connection, key: UploadSessionKey): SessionRow =
    selectSession(connection, key, forUpdate = true).getOrElse(throw DomainFailure(Error.Missing(key)))

  private def selectSession(connection: Connection, key: UploadSessionKey, forUpdate: Boolean): Option[SessionRow] =
    val suffix    = if forUpdate then " FOR UPDATE" else ""
    val statement = connection.prepareStatement(
      s"""SELECT content_type, expected_size, byte_offset, part_count, created_at, expires_at,
         |       phase, committed_blob, commit_lease_id::text, commit_lease_expires_at
         |FROM graviton.upload_session
         |WHERE tenant_id = ? AND upload_session_id = ?$suffix""".stripMargin
    )
    try
      bindKey(statement, key)
      val result = statement.executeQuery()
      try if result.next() then Some(sessionRow(key, result)) else None
      finally result.close()
    finally statement.close()

  private def sessionRow(key: UploadSessionKey, result: ResultSet): SessionRow =
    val mediaType = MediaTypeText.parse(result.getString(1)).fold(message => throw new IllegalArgumentException(message), identity)
    val expected  = Option(result.getObject(2)).map(_ => FileSize.applyUnsafe(result.getLong(2)))
    val offset    = UploadOffset.applyUnsafe(result.getLong(3))
    val count     = UploadPartNumber.applyUnsafe(result.getInt(4))
    val phase     = ResumableUploadPhase.values.find(_.toString == result.getString(7)).getOrElse {
      throw new IllegalStateException(s"invalid upload phase '${result.getString(7)}'")
    }
    val blob      = Option(result.getString(8)).map(value =>
      KeyBits.fromString(value).flatMap(BinaryKey.blob).fold(message => throw new IllegalArgumentException(message), identity)
    )
    val lease     = Option(result.getString(9)).map(id =>
      ResumableUploadLedger.CommitLease(
        UploadLeaseId.applyUnsafe(id),
        result.getTimestamp(10).toInstant,
      )
    )
    SessionRow(
      ResumableUploadSession(
        key,
        UploadIntent(mediaType, expected),
        offset,
        count,
        result.getTimestamp(5).toInstant,
        result.getTimestamp(6).toInstant,
        phase,
        blob,
      ),
      lease,
    )

  private def selectPart(connection: Connection, key: UploadSessionKey, partId: UploadPartId): Option[PartRow] =
    val statement = connection.prepareStatement(
      """SELECT part_id::text, part_number, byte_offset, byte_length, locator,
        |       lease_id::text, lease_expires_at
        |FROM graviton.upload_part
        |WHERE tenant_id = ? AND upload_session_id = ? AND part_id = ?""".stripMargin
    )
    try
      bindKey(statement, key)
      statement.setObject(3, UUID.fromString(partId.value))
      val result = statement.executeQuery()
      try if result.next() then Some(partRow(key, result)) else None
      finally result.close()
    finally statement.close()

  private def selectActiveReservation(connection: Connection, key: UploadSessionKey, now: Instant): Option[UploadPartReservation] =
    val statement = connection.prepareStatement(
      """SELECT part_id::text, part_number, byte_offset, byte_length, locator,
        |       lease_id::text, lease_expires_at
        |FROM graviton.upload_part
        |WHERE tenant_id = ? AND upload_session_id = ? AND byte_length IS NULL AND lease_expires_at > ?
        |LIMIT 1""".stripMargin
    )
    try
      bindKey(statement, key)
      statement.setTimestamp(3, Timestamp.from(now))
      val result = statement.executeQuery()
      try
        if result.next() then
          partRow(key, result) match
            case PartRow.Reserved(value) => Some(value)
            case _                       => None
        else None
      finally result.close()
    finally statement.close()

  private def partRow(key: UploadSessionKey, result: ResultSet): PartRow =
    val id      = UploadPartId.applyUnsafe(result.getString(1))
    val number  = UploadPartNumber.applyUnsafe(result.getInt(2))
    val offset  = UploadOffset.applyUnsafe(result.getLong(3))
    val locator = parseLocator(result.getString(5))
    Option(result.getObject(4)) match
      case Some(_) => PartRow.Completed(ResumableUploadPart(id, number, offset, FileSize.applyUnsafe(result.getLong(4)), locator))
      case None    =>
        PartRow.Reserved(
          UploadPartReservation(
            key,
            id,
            number,
            offset,
            locator,
            UploadLeaseId.applyUnsafe(result.getString(6)),
            result.getTimestamp(7).toInstant,
          )
        )

  private def completedPart(result: ResultSet): ResumableUploadPart =
    ResumableUploadPart(
      UploadPartId.applyUnsafe(result.getString(1)),
      UploadPartNumber.applyUnsafe(result.getInt(2)),
      UploadOffset.applyUnsafe(result.getLong(3)),
      FileSize.applyUnsafe(result.getLong(4)),
      parseLocator(result.getString(5)),
    )

  private def insertReservation(connection: Connection, value: UploadPartReservation, now: Instant): Unit =
    val statement = connection.prepareStatement(
      """INSERT INTO graviton.upload_part
        |  (tenant_id, upload_session_id, part_id, part_number, byte_offset, locator, lease_id, lease_expires_at, created_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    )
    try
      bindKey(statement, value.key)
      statement.setObject(3, UUID.fromString(value.partId.value))
      statement.setInt(4, value.number.value)
      statement.setLong(5, value.offset.value)
      statement.setString(6, value.locator.render)
      statement.setObject(7, UUID.fromString(value.leaseId.value))
      statement.setTimestamp(8, Timestamp.from(value.leaseExpiresAt))
      statement.setTimestamp(9, Timestamp.from(now))
      statement.executeUpdate()
      ()
    finally statement.close()

  private def completePartRow(connection: Connection, reservation: UploadPartReservation, size: FileSize, now: Instant): Unit =
    val statement = connection.prepareStatement(
      """UPDATE graviton.upload_part
        |SET byte_length = ?, lease_id = NULL, lease_expires_at = NULL, completed_at = ?
        |WHERE tenant_id = ? AND upload_session_id = ? AND part_id = ? AND lease_id = ?""".stripMargin
    )
    try
      statement.setLong(1, size.value)
      statement.setTimestamp(2, Timestamp.from(now))
      bindKey(statement, reservation.key, start = 3)
      statement.setObject(5, UUID.fromString(reservation.partId.value))
      statement.setObject(6, UUID.fromString(reservation.leaseId.value))
      if statement.executeUpdate() != 1 then throw DomainFailure(Error.LeaseLost())
    finally statement.close()

  private def updateProgress(connection: Connection, session: ResumableUploadSession): Unit =
    val statement = connection.prepareStatement(
      """UPDATE graviton.upload_session
        |SET byte_offset = ?, part_count = ?, updated_at = clock_timestamp()
        |WHERE tenant_id = ? AND upload_session_id = ?""".stripMargin
    )
    try
      statement.setLong(1, session.offset.value)
      statement.setInt(2, session.partCount.value)
      bindKey(statement, session.key, start = 3)
      statement.executeUpdate()
      ()
    finally statement.close()

  private def deleteExpiredReservations(connection: Connection, key: UploadSessionKey, now: Instant): Unit =
    val statement = connection.prepareStatement(
      """DELETE FROM graviton.upload_part
        |WHERE tenant_id = ? AND upload_session_id = ? AND byte_length IS NULL AND lease_expires_at <= ?""".stripMargin
    )
    try
      bindKey(statement, key)
      statement.setTimestamp(3, Timestamp.from(now))
      statement.executeUpdate()
      ()
    finally statement.close()

  private def bindKey(statement: PreparedStatement, key: UploadSessionKey, start: Int = 1): Unit =
    statement.setObject(start, UUID.fromString(key.tenantId.value))
    statement.setObject(start + 1, UUID.fromString(key.uploadSessionId.value))

  private def ensureCurrent(session: ResumableUploadSession, now: Instant): Unit =
    if ResumableUploadLedger.isExpired(session, now) then throw DomainFailure(Error.Expired(session.key))

  private def ensureOpen(session: ResumableUploadSession): Unit =
    if session.phase != ResumableUploadPhase.Open then throw DomainFailure(Error.InvalidState(session.key, session.phase))

  private def parseLocator(raw: String): BlobLocator =
    val uri = URI.create(raw)
    BlobLocator
      .from(uri.getScheme, uri.getHost, uri.getPath.stripPrefix("/"))
      .fold(message => throw new IllegalArgumentException(message), identity)

object PgResumableUploadRepository:
  private val PageSize        = 256
  private val UniqueViolation = "23505"

  private final case class DomainFailure(error: Error) extends RuntimeException(error.getMessage, error)
  private final case class SessionRow(
    session: ResumableUploadSession,
    commitLease: Option[ResumableUploadLedger.CommitLease],
  )

  private enum PartRow:
    case Completed(value: ResumableUploadPart)
    case Reserved(value: UploadPartReservation)

  val layer: ZLayer[DataSource, Nothing, ResumableUploadRepository] =
    ZLayer.fromFunction((dataSource: DataSource) => new PgResumableUploadRepository(dataSource))
