package graviton.protocol.http

import graviton.security.*
import graviton.security.jwt.HmacJwtVerifier
import zio.*
import zio.http.*
import zio.json.*

import java.util.UUID

/**
 * Development-only token mint endpoint. Active only when a dev shared
 * secret has been configured via `GRAVITON_SECURITY_DEV_SHARED_SECRET`.
 *
 * Never mount this endpoint in production. It exists so an operator or a
 * developer can mint an HS256 JWT locally — no external IdP required —
 * and immediately curl the protected HTTP routes. For production,
 * configure an OIDC issuer and an RS256 verifier instead.
 */
object DevAuthRoutes:

  final case class MintRequest(
    org_id: Option[String] = None,
    principal_id: Option[String] = None,
    caps: Option[Long] = None,
    ttl_seconds: Option[Long] = None,
  )
  object MintRequest:
    given JsonCodec[MintRequest] = DeriveJsonCodec.gen[MintRequest]

  final case class MintResponse(access_token: String, expires_in: Long, token_type: String = "Bearer")
  object MintResponse:
    given JsonCodec[MintResponse] = DeriveJsonCodec.gen[MintResponse]

  /**
   * Build the `/dev/token` route backed by `secret`. The returned routes
   * should only be added to the server when security is enabled AND a
   * non-empty dev secret has been configured.
   */
  def routes(secret: String, issuer: Option[String], audience: Option[String]): Routes[Any, Nothing] =
    Routes(
      Method.POST / "dev" / "token" ->
        Handler.fromFunctionZIO[Request] { req =>
          req.body.asString
            .mapError(err => Response.text(s"failed to read body: ${err.getMessage}").copy(status = Status.BadRequest))
            .flatMap { raw =>
              val parsed =
                if raw.isEmpty then Right(MintRequest())
                else raw.fromJson[MintRequest]
              parsed match
                case Left(err)   =>
                  ZIO.succeed(Response.text(s"invalid JSON: $err").copy(status = Status.BadRequest))
                case Right(body) =>
                  val orgId = body.org_id.flatMap(tryUuid).getOrElse(new UUID(0L, 1L))
                  val pid   = body.principal_id.flatMap(tryUuid).getOrElse(new UUID(0L, 2L))
                  val caps  = body.caps.getOrElse(defaultCaps)
                  val ttl   = body.ttl_seconds.getOrElse(3600L).max(60L).min(86400L)
                  ZIO.clockWith(_.instant).map { now =>
                    val token = HmacJwtVerifier.mint(
                      secret = secret,
                      orgId = orgId,
                      principalId = pid,
                      capabilities = caps,
                      ttlSeconds = ttl,
                      issuer = issuer,
                      audience = audience,
                      nowEpochSeconds = now.getEpochSecond,
                    )
                    Response.json(MintResponse(token, ttl).toJson)
                  }
            }
        }
    )

  private def tryUuid(raw: String): Option[UUID] =
    scala.util.Try(UUID.fromString(raw)).toOption

  /** Default capability bundle granted to dev tokens when `caps` is unset. */
  private val defaultCaps: Long =
    CapabilitySet
      .of(
        Capability.BlobRead,
        Capability.BlobWrite,
        Capability.BlobDelete,
        Capability.DocumentRead,
        Capability.DocumentWrite,
        Capability.ObservabilityRead,
      )
      .mask
