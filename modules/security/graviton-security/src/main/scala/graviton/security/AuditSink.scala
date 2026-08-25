package graviton.security

import zio.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Append-only audit log. Every [[AuditEvent]] goes into `quasar.audit_log`
 * with a per-org hash chain:
 * {{{
 *   row_hash = sha256(prev_hash || canonical_fields)
 *   prev_hash for seq = N is row_hash of seq = N - 1
 *   prev_hash for seq = 1 is 32 zero bytes
 * }}}
 *
 * JDBC insertion is serialized per-org in the process and with a PostgreSQL
 * transaction advisory lock, so the chain stays linear across server nodes.
 */
trait AuditSink:
  def record(event: AuditEvent): IO[SecurityError, Unit]

  /** Shorthand for successful read/write outcomes. */
  def allow(action: String, resource: ResourceRef, bytes: Option[Long] = None): IO[SecurityError, Unit]

  /** Shorthand for capability denials. */
  def deny(action: String, resource: ResourceRef, reason: String): IO[SecurityError, Unit]

  /** Authentication failed (no CallerContext yet). */
  def authFail(action: String, requestId: UUID, reason: String, sourceIp: Option[String]): IO[SecurityError, Unit]

/**
 * One audit row. `seq`, `prev_hash`, `row_hash` are filled by the sink.
 */
final case class AuditEvent(
  action: String,
  resource: ResourceRef,
  outcome: AuditEvent.Outcome,
  reason: Option[String] = None,
  bytes: Option[Long] = None,
)

object AuditEvent:
  enum Outcome(val dbValue: String):
    case Allow    extends Outcome("allow")
    case Deny     extends Outcome("deny")
    case Error    extends Outcome("error")
    case AuthFail extends Outcome("auth_fail")

object AuditSink:

  /** In-memory sink; drops events after collecting them. Tests only. */
  def inMemory: UIO[AuditSink & AuditSink.Inspect] =
    Ref.make(Vector.empty[AuditRecord]).map(ref => new InMemorySink(ref))

  /** JDBC-backed sink with per-org serialisation. */
  def jdbc: URLayer[DataSource, AuditSink] =
    ZLayer.fromZIO {
      for
        ds     <- ZIO.service[DataSource]
        guards <- Ref.Synchronized.make(Map.empty[UUID, Semaphore])
      yield (new JdbcSink(ds, guards)): AuditSink
    }

  /** Test hook exposed by the in-memory sink. */
  trait Inspect:
    def drain: UIO[Vector[AuditRecord]]

  /** A fully-formed row, including chain fields. Exposed to tests. */
  final case class AuditRecord(
    orgId: UUID,
    seq: Long,
    ts: Instant,
    principalId: UUID,
    action: String,
    resource: ResourceRef,
    requestId: UUID,
    outcome: AuditEvent.Outcome,
    reason: Option[String],
    bytes: Option[Long],
    prevHash: Array[Byte],
    rowHash: Array[Byte],
  )

  private val ZeroHash: Array[Byte] = new Array[Byte](32)

  /**
   * Canonical serialisation for hashing. Must match the Postgres
   * `verify_audit_chain` function byte-for-byte.
   */
  private[security] def canonicalPayload(rec: AuditRecord): Array[Byte] =
    val buf = new StringBuilder()
    buf.append(rec.orgId.toString)
    buf.append('|')
    buf.append(rec.seq.toString)
    buf.append('|')
    buf.append(rec.ts.toString) // ISO-8601 instant, stable across JVMs
    buf.append('|')
    buf.append(rec.principalId.toString)
    buf.append('|')
    buf.append(rec.action)
    buf.append('|')
    buf.append(rec.resource.dbKind)
    buf.append('|')
    buf.append(rec.resource.id.map(_.toString).getOrElse(""))
    buf.append('|')
    buf.append(rec.requestId.toString)
    buf.append('|')
    buf.append(rec.outcome.dbValue)
    buf.append('|')
    buf.append(rec.reason.getOrElse(""))
    buf.append('|')
    buf.append(rec.bytes.map(_.toString).getOrElse(""))
    buf.toString.getBytes(StandardCharsets.UTF_8)

  private[security] def computeRowHash(rec: AuditRecord): Array[Byte] =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(rec.prevHash)
    digest.update(canonicalPayload(rec))
    digest.digest()

  // ---- In-memory ----------------------------------------------------------

  private final class InMemorySink(ref: Ref[Vector[AuditRecord]]) extends AuditSink with Inspect:

    def drain: UIO[Vector[AuditRecord]] = ref.getAndSet(Vector.empty)

    def record(event: AuditEvent): IO[SecurityError, Unit] =
      CallerContext.required.flatMap(ctx => append(ctx, event))

    def allow(action: String, resource: ResourceRef, bytes: Option[Long]): IO[SecurityError, Unit] =
      record(AuditEvent(action, resource, AuditEvent.Outcome.Allow, bytes = bytes))

    def deny(action: String, resource: ResourceRef, reason: String): IO[SecurityError, Unit] =
      record(AuditEvent(action, resource, AuditEvent.Outcome.Deny, reason = Some(reason)))

    def authFail(action: String, requestId: UUID, reason: String, sourceIp: Option[String]): IO[SecurityError, Unit] =
      ZIO.clockWith(_.instant).flatMap { now =>
        ref.update { prior =>
          val zeroOrg      = new UUID(0L, 0L)
          val anonymous    = new UUID(0L, 0L)
          val zeroOrgPrior = prior.filter(_.orgId == zeroOrg)
          val last         = zeroOrgPrior.lastOption.map(_.rowHash).getOrElse(ZeroHash)
          val seq          = zeroOrgPrior.size.toLong + 1
          val record       = AuditRecord(
            orgId = zeroOrg,
            seq = seq,
            ts = now,
            principalId = anonymous,
            action = action,
            resource = ResourceRef(ResourceKind.Blob, None),
            requestId = requestId,
            outcome = AuditEvent.Outcome.AuthFail,
            reason = Some(reason),
            bytes = None,
            prevHash = last,
            rowHash = Array.emptyByteArray,
          )
          val withHash     = record.copy(rowHash = computeRowHash(record))
          prior :+ withHash
        }
      }

    private def append(ctx: CallerContext, event: AuditEvent): UIO[Unit] =
      ZIO.clockWith(_.instant).flatMap { now =>
        ref.update { prior =>
          val orgPrior = prior.filter(_.orgId == ctx.orgId)
          val last     = orgPrior.lastOption.map(_.rowHash).getOrElse(ZeroHash)
          val seq      = orgPrior.size.toLong + 1
          val record   = AuditRecord(
            orgId = ctx.orgId,
            seq = seq,
            ts = now,
            principalId = ctx.principalId,
            action = event.action,
            resource = event.resource,
            requestId = ctx.requestId,
            outcome = event.outcome,
            reason = event.reason,
            bytes = event.bytes,
            prevHash = last,
            rowHash = Array.emptyByteArray,
          )
          val withHash = record.copy(rowHash = computeRowHash(record))
          prior :+ withHash
        }
      }

  // ---- JDBC ---------------------------------------------------------------

  private final class JdbcSink(ds: DataSource, guardsRef: Ref.Synchronized[Map[UUID, Semaphore]]) extends AuditSink:

    def record(event: AuditEvent): IO[SecurityError, Unit] =
      CallerContext.required.flatMap(ctx => appendWithLock(ctx, event))

    def allow(action: String, resource: ResourceRef, bytes: Option[Long]): IO[SecurityError, Unit] =
      record(AuditEvent(action, resource, AuditEvent.Outcome.Allow, bytes = bytes))

    def deny(action: String, resource: ResourceRef, reason: String): IO[SecurityError, Unit] =
      record(AuditEvent(action, resource, AuditEvent.Outcome.Deny, reason = Some(reason)))

    def authFail(action: String, requestId: UUID, reason: String, sourceIp: Option[String]): IO[SecurityError, Unit] =
      // Auth-failures have no org_id and no chain; we log them via ZIO logging,
      // SIEM pickups should tail the app log. Persisting them per-org is
      // risky (no trusted org id) so we keep them out of quasar.audit_log.
      ZIO.logWarning(s"audit.auth_fail action=$action request_id=$requestId reason=$reason ip=${sourceIp.getOrElse("-")}")

    private def appendWithLock(ctx: CallerContext, event: AuditEvent): IO[SecurityError, Unit] =
      guardFor(ctx.orgId).flatMap { sem =>
        sem.withPermit {
          ZIO.clockWith(_.instant).flatMap { now =>
            TenantScopedBlocking
              .attemptBlockingWith(ctx)(insertOne(ctx, event, now))
              .mapError(err => SecurityError.AuditFailure(s"audit insert failed: ${err.getMessage}", Some(err)))
              .unit
          }
        }
      }

    private def guardFor(orgId: UUID): UIO[Semaphore] =
      guardsRef.modifyZIO { current =>
        current.get(orgId) match
          case Some(sem) => ZIO.succeed((sem, current))
          case None      => Semaphore.make(1L).map(sem => (sem, current.updated(orgId, sem)))
      }

    private def insertOne(ctx: CallerContext, event: AuditEvent, now: Instant): Unit =
      val conn = ds.getConnection
      conn.setAutoCommit(false)
      try
        setTenantContext(conn, ctx)
        // Database-level transaction lock keeps the sequence and hash chain
        // linear across every server process, not only fibers in this JVM.
        val lockStmt = conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        try
          lockStmt.setString(1, ctx.orgId.toString)
          val result = lockStmt.executeQuery()
          try
            val _ = result.next()
          finally result.close()
        finally lockStmt.close()

        val seqStmt         = conn.prepareStatement(
          "SELECT coalesce(max(seq), 0) + 1, coalesce((SELECT row_hash FROM quasar.audit_log WHERE org_id = ? ORDER BY seq DESC LIMIT 1), decode('0000000000000000000000000000000000000000000000000000000000000000','hex')) FROM quasar.audit_log WHERE org_id = ?"
        )
        val (seq, prevHash) =
          try
            seqStmt.setObject(1, ctx.orgId)
            seqStmt.setObject(2, ctx.orgId)
            val rs = seqStmt.executeQuery()
            try
              rs.next()
              (rs.getLong(1), Option(rs.getBytes(2)).getOrElse(ZeroHash))
            finally rs.close()
          finally seqStmt.close()

        val rec     = AuditRecord(
          orgId = ctx.orgId,
          seq = seq,
          ts = now,
          principalId = ctx.principalId,
          action = event.action,
          resource = event.resource,
          requestId = ctx.requestId,
          outcome = event.outcome,
          reason = event.reason,
          bytes = event.bytes,
          prevHash = prevHash,
          rowHash = Array.emptyByteArray,
        )
        val rowHash = computeRowHash(rec)

        val ins = conn.prepareStatement(
          """INSERT INTO quasar.audit_log
            | (org_id, seq, ts, principal_id, action, resource_kind, resource_id,
            |  request_id, source_ip, user_agent, outcome, reason, bytes, prev_hash, row_hash)
            | VALUES (?, ?, ?, ?, ?, ?::quasar.resource_kind, ?, ?, ?::inet, ?, ?::quasar.audit_outcome, ?, ?, ?, ?)
            |""".stripMargin
        )
        try
          ins.setObject(1, ctx.orgId)
          ins.setLong(2, seq)
          ins.setTimestamp(3, Timestamp.from(now))
          ins.setObject(4, ctx.principalId)
          ins.setString(5, event.action)
          ins.setString(6, event.resource.dbKind)
          event.resource.id match
            case Some(id) => ins.setObject(7, id)
            case None     => ins.setNull(7, java.sql.Types.OTHER)
          ins.setObject(8, ctx.requestId)
          ctx.sourceIp match
            case Some(ip) => ins.setString(9, ip)
            case None     => ins.setNull(9, java.sql.Types.OTHER)
          ctx.userAgent match
            case Some(ua) => ins.setString(10, ua)
            case None     => ins.setNull(10, java.sql.Types.VARCHAR)
          ins.setString(11, event.outcome.dbValue)
          event.reason match
            case Some(r) => ins.setString(12, r)
            case None    => ins.setNull(12, java.sql.Types.VARCHAR)
          event.bytes match
            case Some(b) => ins.setLong(13, b)
            case None    => ins.setNull(13, java.sql.Types.BIGINT)
          ins.setBytes(14, prevHash)
          ins.setBytes(15, rowHash)
          ins.executeUpdate()
          conn.commit()
        finally ins.close()
      catch
        case err: Throwable =>
          try conn.rollback()
          catch case _: Throwable => ()
          throw err
      finally
        try conn.setAutoCommit(true)
        catch case _: Throwable => ()
        conn.close()

    private def setTenantContext(conn: java.sql.Connection, ctx: CallerContext): Unit =
      val statement = conn.prepareStatement(
        "SELECT set_config('app.org_id', ?, true), set_config('app.principal_id', ?, true)"
      )
      try
        statement.setString(1, ctx.orgId.toString)
        statement.setString(2, ctx.principalId.toString)
        val result = statement.executeQuery()
        try
          val _ = result.next()
        finally result.close()
      finally statement.close()
