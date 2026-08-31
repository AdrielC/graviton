package graviton.protocol.http

import graviton.security.*
import graviton.security.jwt.HmacJwtVerifier
import graviton.streams.BoundedByteStream
import zio.*
import zio.http.*
import zio.json.*

import java.util.UUID
import java.nio.charset.StandardCharsets

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

  private val DefaultOrgId       = UUID.fromString("00000000-0000-4000-8000-000000000001")
  private val DefaultPrincipalId = UUID.fromString("00000000-0000-4000-8000-000000000002")

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
          BoundedByteStream
            .collectControlPlane(req.body.asStream)
            .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
            .mapError {
              case _: BoundedByteStream.LimitExceeded => Response.status(Status.RequestEntityTooLarge)
              case _                                  => Response.text("failed to read body").copy(status = Status.BadRequest)
            }
            .flatMap { raw =>
              val parsed =
                if raw.isEmpty then Right(MintRequest())
                else raw.fromJson[MintRequest]
              parsed match
                case Left(err)   =>
                  ZIO.succeed(Response.text(s"invalid JSON: $err").copy(status = Status.BadRequest))
                case Right(body) =>
                  val identities = for
                    orgId <- standardUuid(body.org_id, DefaultOrgId, "org_id")
                    pid   <- standardUuid(body.principal_id, DefaultPrincipalId, "principal_id")
                  yield (orgId, pid)
                  identities match
                    case Left(message)       => ZIO.succeed(Response.text(message).copy(status = Status.BadRequest))
                    case Right((orgId, pid)) =>
                      val caps = body.caps.getOrElse(defaultCaps)
                      val ttl  = body.ttl_seconds.getOrElse(3600L).max(60L).min(86400L)
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

  private def standardUuid(raw: Option[String], default: UUID, field: String): Either[String, UUID] =
    raw match
      case None        => Right(default)
      case Some(value) =>
        scala.util
          .Try(UUID.fromString(value))
          .toEither
          .left
          .map(_ => s"$field must be a canonical UUID")
          .filterOrElse(
            uuid => uuid.toString == value && uuid.variant == 2 && uuid.version >= 1 && uuid.version <= 8,
            s"$field must be a canonical UUID",
          )

  /** Default capability bundle granted to dev tokens when `caps` is unset. */
  private val defaultCaps: Long =
    CapabilitySet
      .of(
        Capability.BlobRead,
        Capability.BlobWrite,
        Capability.BlobDelete,
        Capability.ObservabilityRead,
        Capability.AuditRead,
      )
      .mask
