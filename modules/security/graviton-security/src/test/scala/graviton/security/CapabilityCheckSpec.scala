package graviton.security

import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object CapabilityCheckSpec extends ZIOSpecDefault:

  private def fixture(caps: CapabilitySet): CallerContext =
    CallerContext(
      orgId = UUID.randomUUID(),
      principalId = UUID.randomUUID(),
      capabilities = caps,
      jti = "jti-1",
      tokenExpiresAt = Instant.parse("2099-01-01T00:00:00Z"),
      requestId = UUID.randomUUID(),
    )

  def spec: Spec[TestEnvironment & zio.Scope, Any] = suite("CapabilityCheck.tokenOnly")(
    test("grants when the caller has the required capability") {
      val ctx   = fixture(CapabilitySet.of(Capability.BlobRead))
      val check = CapabilityCheck.tokenOnly
      for result <- CallerContext.scopedWith(ctx)(check.require(Capability.BlobRead, ResourceRef(ResourceKind.Blob, None)).exit)
      yield assert(result)(succeeds(isUnit))
    },
    test("denies when the capability is missing") {
      val ctx   = fixture(CapabilitySet.of(Capability.BlobRead))
      val check = CapabilityCheck.tokenOnly
      for result <- CallerContext.scopedWith(ctx)(check.require(Capability.BlobWrite, ResourceRef(ResourceKind.Blob, None)).exit)
      yield assert(result)(fails(isSubtype[SecurityError.Forbidden](anything)))
    },
    test("fails when no caller context is bound to the fiber") {
      val check = CapabilityCheck.tokenOnly
      for result <- check.require(Capability.BlobRead, ResourceRef(ResourceKind.Blob, None)).exit
      yield assert(result)(fails(isSubtype[SecurityError.Unauthenticated](anything)))
    },
    test("effective returns the caller's token caps when no resource id is given") {
      val ctx   = fixture(CapabilitySet.of(Capability.BlobRead, Capability.BlobWrite))
      val check = CapabilityCheck.tokenOnly
      for result <- CallerContext.scopedWith(ctx)(check.effective(ResourceRef(ResourceKind.Observability, None)))
      yield assert(result.mask)(equalTo(CapabilitySet.of(Capability.BlobRead, Capability.BlobWrite).mask))
    },
  )

end CapabilityCheckSpec
