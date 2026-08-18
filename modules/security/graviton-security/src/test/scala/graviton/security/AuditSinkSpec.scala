package graviton.security

import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

object AuditSinkSpec extends ZIOSpecDefault:

  private def mkContext(orgId: UUID): CallerContext =
    CallerContext(
      orgId = orgId,
      principalId = UUID.randomUUID(),
      capabilities = CapabilitySet.of(Capability.BlobRead),
      jti = "t",
      tokenExpiresAt = Instant.parse("2099-01-01T00:00:00Z"),
      requestId = UUID.randomUUID(),
    )

  private def rehash(prev: Array[Byte], payload: Array[Byte]): Array[Byte] =
    val d = MessageDigest.getInstance("SHA-256")
    d.update(prev)
    d.update(payload)
    d.digest()

  def spec: Spec[TestEnvironment & zio.Scope, Any] = suite("AuditSink.inMemory hash chain")(
    test("first row's prev_hash is 32 zero bytes") {
      for
        sink <- AuditSink.inMemory
        ctx   = mkContext(UUID.randomUUID())
        _    <- CallerContext.scopedWith(ctx)(
                  sink.allow("blob.read", ResourceRef(ResourceKind.Blob, Some(UUID.randomUUID())), Some(10L))
                )
        rows <- sink.drain
      yield assert(rows.head.prevHash.toSeq)(equalTo(Seq.fill(32)(0.toByte)))
    },
    test("each subsequent row's prev_hash equals the previous row's row_hash") {
      for
        sink <- AuditSink.inMemory
        ctx   = mkContext(UUID.randomUUID())
        _    <- CallerContext.scopedWith(ctx)(
                  sink.allow("blob.read", ResourceRef(ResourceKind.Blob, None)) *>
                    sink.allow("blob.write", ResourceRef(ResourceKind.Blob, None)) *>
                    sink.deny("blob.delete", ResourceRef(ResourceKind.Blob, None), "nope")
                )
        rows <- sink.drain
      yield assert(rows.size)(equalTo(3)) &&
        assert(rows(1).prevHash.toSeq)(equalTo(rows(0).rowHash.toSeq)) &&
        assert(rows(2).prevHash.toSeq)(equalTo(rows(1).rowHash.toSeq))
    },
    test("row_hash equals sha256(prev_hash || canonicalPayload)") {
      for
        sink    <- AuditSink.inMemory
        ctx      = mkContext(UUID.randomUUID())
        _       <- CallerContext.scopedWith(ctx)(
                     sink.allow("blob.read", ResourceRef(ResourceKind.Blob, Some(UUID.randomUUID())))
                   )
        rows    <- sink.drain
        row      = rows.head
        expected = rehash(row.prevHash, AuditSink.canonicalPayload(row))
      yield assert(row.rowHash.toSeq)(equalTo(expected.toSeq))
    },
    test("chains are independent per-org") {
      for
        sink <- AuditSink.inMemory
        ctxA  = mkContext(UUID.randomUUID())
        ctxB  = mkContext(UUID.randomUUID())
        _    <- CallerContext.scopedWith(ctxA)(sink.allow("a.1", ResourceRef(ResourceKind.Blob, None)))
        _    <- CallerContext.scopedWith(ctxB)(sink.allow("b.1", ResourceRef(ResourceKind.Blob, None)))
        _    <- CallerContext.scopedWith(ctxA)(sink.allow("a.2", ResourceRef(ResourceKind.Blob, None)))
        rows <- sink.drain
        aRows = rows.filter(_.orgId == ctxA.orgId)
        bRows = rows.filter(_.orgId == ctxB.orgId)
      yield assert(aRows.size)(equalTo(2)) &&
        assert(bRows.size)(equalTo(1)) &&
        assert(aRows(0).seq)(equalTo(1L)) &&
        assert(aRows(1).seq)(equalTo(2L)) &&
        assert(bRows(0).seq)(equalTo(1L)) &&
        assert(aRows(1).prevHash.toSeq)(equalTo(aRows(0).rowHash.toSeq))
    },
  )

end AuditSinkSpec
