package graviton.security

import graviton.security.jwt.ClaimMapping
import zio.test.*
import zio.test.Assertion.*

import java.util.UUID

object ClaimMappingSpec extends ZIOSpecDefault:

  private val now = 1_700_000_000L

  private val baseClaims = ClaimMapping.Claims(
    sub = Some(UUID.randomUUID().toString),
    orgId = Some(UUID.randomUUID().toString),
    principalId = None,
    jti = Some("jti-123"),
    exp = Some(now + 300),
    nbf = None,
    scope = Some("blob.read observability.read"),
    capsMask = None,
  )

  def spec: Spec[TestEnvironment & zio.Scope, Any] = suite("ClaimMapping.toContext")(
    test("happy path: maps scope string to capabilities") {
      val requestId = UUID.randomUUID()
      val ctx       = ClaimMapping.toContext(baseClaims, requestId, now, clockSkewSeconds = 5L)
      assert(ctx.map(_.capabilities.contains(Capability.BlobRead)))(isRight(isTrue)) &&
      assert(ctx.map(_.capabilities.contains(Capability.ObservabilityRead)))(isRight(isTrue)) &&
      assert(ctx.map(_.requestId))(isRight(equalTo(requestId)))
    },
    test("fails if `exp` missing") {
      val c = baseClaims.copy(exp = None)
      assert(ClaimMapping.toContext(c, UUID.randomUUID(), now, 0L))(isLeft(isSubtype[SecurityError.Unauthenticated](anything)))
    },
    test("fails if token expired beyond the clock skew") {
      val c = baseClaims.copy(exp = Some(now - 10))
      assert(ClaimMapping.toContext(c, UUID.randomUUID(), now, clockSkewSeconds = 5L))(
        isLeft(isSubtype[SecurityError.Unauthenticated](anything))
      )
    },
    test("allows token within the clock skew window") {
      val c = baseClaims.copy(exp = Some(now - 2))
      assert(ClaimMapping.toContext(c, UUID.randomUUID(), now, clockSkewSeconds = 5L).isRight)(isTrue)
    },
    test("fails if nbf is in the future") {
      val c = baseClaims.copy(nbf = Some(now + 30))
      assert(ClaimMapping.toContext(c, UUID.randomUUID(), now, 0L))(isLeft(anything))
    },
    test("fails if org_id missing") {
      val c = baseClaims.copy(orgId = None)
      assert(ClaimMapping.toContext(c, UUID.randomUUID(), now, 0L))(isLeft(anything))
    },
    test("fails if org_id is not a UUID") {
      val c = baseClaims.copy(orgId = Some("not-a-uuid"))
      assert(ClaimMapping.toContext(c, UUID.randomUUID(), now, 0L))(isLeft(anything))
    },
    test("merges scope string with numeric caps claim") {
      val c   = baseClaims.copy(scope = Some("blob.read"), capsMask = Some(Capability.AuditRead.bit))
      val ctx = ClaimMapping.toContext(c, UUID.randomUUID(), now, 0L)
      assert(ctx.map(_.capabilities.contains(Capability.BlobRead)))(isRight(isTrue)) &&
      assert(ctx.map(_.capabilities.contains(Capability.AuditRead)))(isRight(isTrue))
    },
  )

end ClaimMappingSpec
