package graviton.backend.pg

import graviton.runtime.stores.{BlobManifestRepo, ManifestReferenceSource, StoreError, StoreOperation}
import graviton.runtime.tenant.*
import graviton.runtime.upload.TenantId
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.Instant
import java.util.{HexFormat, UUID}
import javax.sql.DataSource
import scala.util.Try

/**
 * Durable, immutable tenant-to-storage-domain membership for one maintenance
 * cycle. Capture runs in a repeatable-read transaction and never materializes
 * the tenant population in heap.
 */
final class PgTenantDomainSnapshot(dataSource: DataSource):
  import PgTenantDomainSnapshot.*

  def capture(cellId: TenantCellId): IO[StoreError, SnapshotReport] =
    blocking {
      val connection = dataSource.getConnection()
      try
        connection.setAutoCommit(false)
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
        val snapshotId = UUID.randomUUID()
        insertProvisional(connection, snapshotId, cellId)
        val digest     = MessageDigest.getInstance("SHA-256")
        val count      = streamPoliciesIntoSnapshot(connection, snapshotId, cellId, digest)
        val hash       = digest.digest()
        finishSnapshot(connection, snapshotId, count, hash)
        connection.commit()
        SnapshotReport(snapshotId, cellId, count, HexFormat.of().formatHex(hash), Instant.now())
      catch
        case error: Throwable =>
          try connection.rollback()
          catch case rollback: Throwable => error.addSuppressed(rollback)
          throw error
      finally connection.close()
    }

  def latest(cellId: TenantCellId): IO[StoreError, Option[SnapshotReport]] =
    blocking {
      val connection = dataSource.getConnection()
      try
        val statement = connection.prepareStatement(
          """SELECT snapshot_id, member_count, encode(membership_sha256, 'hex'), captured_at
            |FROM graviton.tenant_domain_snapshot
            |WHERE cell_id = ?
            |ORDER BY captured_at DESC, snapshot_id DESC
            |LIMIT 1""".stripMargin
        )
        try
          statement.setString(1, cellId.value)
          val result = statement.executeQuery()
          try
            if result.next() then
              Some(
                SnapshotReport(
                  result.getObject(1, classOf[UUID]),
                  cellId,
                  result.getLong(2),
                  result.getString(3),
                  result.getTimestamp(4).toInstant,
                )
              )
            else None
          finally result.close()
        finally statement.close()
      finally connection.close()
    }

  /** Domains are distinct and deterministic, and the JDBC cursor is bounded. */
  def streamDomains(snapshotId: UUID): ZStream[Any, StoreError, StorageDomainId] =
    cursor(
      """SELECT DISTINCT storage_domain_id
        |FROM graviton.tenant_domain_snapshot_member
        |WHERE snapshot_id = ?
        |ORDER BY storage_domain_id""".stripMargin,
      statement => statement.setObject(1, snapshotId),
      result => refine(StorageDomainId.either(result.getString(1)), "storage domain"),
    )

  /**
   * Resolve repositories from the immutable snapshot without collecting them.
   * The factory is invoked sequentially so tenant count cannot multiply open
   * manifest cursors.
   */
  def references(
    snapshotId: UUID,
    domain: StorageDomainId,
  )(
    repository: (TenantId, StorageDomainId) => BlobManifestRepo
  ): ManifestReferenceSource =
    ManifestReferenceSource.streaming(
      cursor(
        """SELECT tenant_id
          |FROM graviton.tenant_domain_snapshot_member
          |WHERE snapshot_id = ? AND storage_domain_id = ?
          |ORDER BY tenant_id""".stripMargin,
        statement =>
          statement.setObject(1, snapshotId)
          statement.setString(2, domain.value)
        ,
        result => refine(TenantId.either(result.getObject(1, classOf[UUID]).toString), "tenant id"),
      ).map(tenant => repository(tenant, domain))
    )

  /**
   * Bind a stable repair namespace to the captured membership. A changed
   * membership invalidates both its offset and failures atomically, avoiding
   * stale cursors without allocating one journal namespace per snapshot.
   */
  def beginRepairEpoch(namespace: String, membershipSha256: String): IO[StoreError, Boolean] =
    if namespace.isEmpty || namespace.length > 128 then
      ZIO.fail(StoreError.InvalidInput(StoreOperation.Repair, "repair namespace must contain between 1 and 128 characters"))
    else
      ZIO
        .fromEither(
          Try(HexFormat.of().parseHex(membershipSha256)).toEither.left
            .map(_ => StoreError.InvalidInput(StoreOperation.Repair, "membership digest must be 64 hexadecimal characters"))
            .flatMap(bytes =>
              if bytes.length == 32 then Right(bytes)
              else Left(StoreError.InvalidInput(StoreOperation.Repair, "membership digest must be 64 hexadecimal characters"))
            )
        )
        .flatMap { digest =>
          blocking {
            val connection = dataSource.getConnection()
            try
              connection.setAutoCommit(false)
              val inserted = insertRepairEpoch(connection, namespace, digest)
              val current  = lockRepairEpoch(connection, namespace)
              val changed  = !java.util.Arrays.equals(current, digest)
              if changed then
                updateRepairEpoch(connection, namespace, digest)
                resetRepairJournal(connection, namespace)
              connection.commit()
              inserted || changed
            catch
              case error: Throwable =>
                try connection.rollback()
                catch case rollback: Throwable => error.addSuppressed(rollback)
                throw error
            finally connection.close()
          }
        }

  /** Keep bounded audit history after a successful maintenance cycle. */
  def retainLatest(cellId: TenantCellId, count: Int): IO[StoreError, Long] =
    if count < 1 then ZIO.fail(StoreError.InvalidInput(StoreOperation.Repair, "snapshot retention must be positive"))
    else
      blocking {
        val connection = dataSource.getConnection()
        try
          val statement = connection.prepareStatement(
            """DELETE FROM graviton.tenant_domain_snapshot
              |WHERE cell_id = ? AND snapshot_id IN (
              |  SELECT snapshot_id
              |  FROM graviton.tenant_domain_snapshot
              |  WHERE cell_id = ?
              |  ORDER BY captured_at DESC, snapshot_id DESC
              |  OFFSET ?
              |)""".stripMargin
          )
          try
            statement.setString(1, cellId.value)
            statement.setString(2, cellId.value)
            statement.setInt(3, count)
            statement.executeUpdate().toLong
          finally statement.close()
        finally connection.close()
      }

  private def streamPoliciesIntoSnapshot(
    connection: Connection,
    snapshotId: UUID,
    cellId: TenantCellId,
    digest: MessageDigest,
  ): Long =
    val select = connection.prepareStatement(
      """SELECT tenant_id, deduplication_domain, revision
        |FROM graviton.tenant_storage_policy
        |WHERE cell_id = ?
        |ORDER BY tenant_id""".stripMargin
    )
    val insert = connection.prepareStatement(
      """INSERT INTO graviton.tenant_domain_snapshot_member(
        |  snapshot_id, storage_domain_id, tenant_id, policy_revision
        |) VALUES (?, ?, ?::uuid, ?)""".stripMargin
    )
    try
      select.setFetchSize(BatchSize)
      select.setString(1, cellId.value)
      val result = select.executeQuery()
      try
        var count = 0L
        var batch = 0
        while result.next() do
          val tenant   = refine(TenantId.either(result.getObject(1, classOf[UUID]).toString), "tenant id")
          val scope    = Option(result.getString(2)) match
            case None        => DeduplicationScope.Isolated
            case Some(value) => DeduplicationScope.Shared(refine(DeduplicationDomainId.either(value), "deduplication domain"))
          val revision = refine(TenantPolicyRevision.either(result.getLong(3)), "policy revision")
          val domain   = TenantRoute(tenant, scope).storageDomain
          updateDigest(digest, domain, tenant, revision)
          insert.setObject(1, snapshotId)
          insert.setString(2, domain.value)
          insert.setString(3, tenant.value)
          insert.setLong(4, revision.value)
          insert.addBatch()
          count += 1L
          batch += 1
          if batch == BatchSize then
            insert.executeBatch()
            batch = 0
        if batch > 0 then
          val _ = insert.executeBatch()
        count
      finally result.close()
    finally
      try select.close()
      finally insert.close()

  private def insertProvisional(connection: Connection, snapshotId: UUID, cellId: TenantCellId): Unit =
    val statement = connection.prepareStatement(
      """INSERT INTO graviton.tenant_domain_snapshot(
        |  snapshot_id, cell_id, member_count, membership_sha256
        |) VALUES (?, ?, 0, ?)""".stripMargin
    )
    try
      statement.setObject(1, snapshotId)
      statement.setString(2, cellId.value)
      statement.setBytes(3, Array.fill[Byte](32)(0))
      statement.executeUpdate()
      ()
    finally statement.close()

  private def insertRepairEpoch(connection: Connection, namespace: String, digest: Array[Byte]): Boolean =
    val statement = connection.prepareStatement(
      """INSERT INTO graviton.tenant_domain_repair_epoch(namespace, membership_sha256)
        |VALUES (?, ?)
        |ON CONFLICT (namespace) DO NOTHING""".stripMargin
    )
    try
      statement.setString(1, namespace)
      statement.setBytes(2, digest)
      statement.executeUpdate() == 1
    finally statement.close()

  private def lockRepairEpoch(connection: Connection, namespace: String): Array[Byte] =
    val statement = connection.prepareStatement(
      "SELECT membership_sha256 FROM graviton.tenant_domain_repair_epoch WHERE namespace = ? FOR UPDATE"
    )
    try
      statement.setString(1, namespace)
      val result = statement.executeQuery()
      try
        if result.next() then result.getBytes(1)
        else throw new IllegalStateException("repair epoch disappeared during initialization")
      finally result.close()
    finally statement.close()

  private def updateRepairEpoch(connection: Connection, namespace: String, digest: Array[Byte]): Unit =
    val statement = connection.prepareStatement(
      "UPDATE graviton.tenant_domain_repair_epoch SET membership_sha256 = ?, updated_at = clock_timestamp() WHERE namespace = ?"
    )
    try
      statement.setBytes(1, digest)
      statement.setString(2, namespace)
      if statement.executeUpdate() != 1 then throw new IllegalStateException("repair epoch disappeared during reset")
    finally statement.close()

  private def resetRepairJournal(connection: Connection, namespace: String): Unit =
    val cursor = connection.prepareStatement("DELETE FROM graviton.repair_state WHERE namespace = ?")
    val failed = connection.prepareStatement("DELETE FROM graviton.repair_dead_letter WHERE namespace = ?")
    try
      cursor.setString(1, namespace)
      cursor.executeUpdate()
      failed.setString(1, namespace)
      failed.executeUpdate()
      ()
    finally
      try cursor.close()
      finally failed.close()

  private def finishSnapshot(connection: Connection, snapshotId: UUID, count: Long, digest: Array[Byte]): Unit =
    val statement = connection.prepareStatement(
      """UPDATE graviton.tenant_domain_snapshot
        |SET member_count = ?, membership_sha256 = ?
        |WHERE snapshot_id = ?""".stripMargin
    )
    try
      statement.setLong(1, count)
      statement.setBytes(2, digest)
      statement.setObject(3, snapshotId)
      if statement.executeUpdate() != 1 then throw new IllegalStateException("tenant domain snapshot disappeared during capture")
    finally statement.close()

  private def cursor[A](
    sql: String,
    bind: PreparedStatement => Unit,
    read: ResultSet => A,
  ): ZStream[Any, StoreError, A] =
    ZStream.acquireReleaseWith(openCursor(sql, bind))(closeCursor).flatMap { current =>
      ZStream.unfoldZIO(current) { value =>
        blocking(if value.result.next() then Some(read(value.result) -> value) else None)
      }
    }

  private def openCursor(sql: String, bind: PreparedStatement => Unit): IO[StoreError, Cursor] =
    blocking {
      val connection = dataSource.getConnection()
      try
        connection.setReadOnly(true)
        connection.setAutoCommit(false)
        val statement = connection.prepareStatement(sql)
        try
          statement.setFetchSize(BatchSize)
          bind(statement)
          Cursor(connection, statement, statement.executeQuery())
        catch
          case error: Throwable =>
            statement.close()
            throw error
      catch
        case error: Throwable =>
          connection.close()
          throw error
    }

  private def closeCursor(cursor: Cursor): UIO[Unit] =
    graviton.runtime.lifecycle.ResourceFinalizer.closeBlocking("PostgreSQL tenant snapshot cursor") {
      try cursor.result.close()
      finally
        try cursor.statement.close()
        finally
          try cursor.connection.rollback()
          finally cursor.connection.close()
    }

  private def blocking[A](effect: => A): IO[StoreError, A] =
    ZIO.attemptBlocking(effect).mapError(PgStoreError.fromThrowable(StoreOperation.Repair, retryUnknown = true))

object PgTenantDomainSnapshot:
  private val BatchSize = 256

  final case class SnapshotReport(
    snapshotId: UUID,
    cellId: TenantCellId,
    memberCount: Long,
    membershipSha256: String,
    capturedAt: Instant,
  )

  private final case class Cursor(connection: Connection, statement: PreparedStatement, result: ResultSet)

  private def refine[A](value: Either[String, A], field: String): A =
    value.fold(reason => throw new IllegalStateException(s"invalid $field in tenant policy snapshot: $reason"), identity)

  private def updateDigest(
    digest: MessageDigest,
    domain: StorageDomainId,
    tenant: TenantId,
    revision: TenantPolicyRevision,
  ): Unit =
    digest.update(domain.value.getBytes(StandardCharsets.UTF_8))
    digest.update(0.toByte)
    digest.update(tenant.value.getBytes(StandardCharsets.UTF_8))
    digest.update(0.toByte)
    digest.update(revision.value.toString.getBytes(StandardCharsets.UTF_8))
    digest.update('\n'.toByte)
