package graviton.security

import zio.*
import zio.Config

/**
 * Typed security configuration loaded from environment variables under the
 * `GRAVITON_SECURITY_*` prefix (e.g. `GRAVITON_SECURITY_ENABLED`,
 * `GRAVITON_SECURITY_OIDC_ISSUER`). Kept in its own module so
 * graviton-security has no dependency on runtime config primitives beyond
 * zio-config.
 */
final case class SecurityConfig(
  enabled: Boolean,
  oidcIssuer: Option[String],
  oidcAudience: Option[String],
  jwksCacheTtl: Duration,
  requireTls: Boolean,
  corsAllowedOrigins: List[String],
  rateLimitPerPrincipalPerSec: Long,
  rateLimitUploadBytesPerSec: Long,
  maxRequestBytes: Long,
  auditFlushInterval: Duration,
  kmsKeyArn: Option[String],
  clockSkewSeconds: Long,
  devSharedSecret: Option[String] = None,
  auditBackend: String = "memory",
):
  /** Fail-fast validation used by Main at startup when security is enabled. */
  def validate: Either[String, SecurityConfig] =
    if !enabled then Right(this)
    else
      for
        issuer <- oidcIssuer.toRight("GRAVITON_SEC_OIDC_ISSUER is required when security is enabled")
        _      <- oidcAudience.toRight("GRAVITON_SEC_OIDC_AUDIENCE is required when security is enabled")
      yield this

object SecurityConfig:

  val Default: SecurityConfig = SecurityConfig(
    enabled = false,
    oidcIssuer = None,
    oidcAudience = None,
    jwksCacheTtl = 10.minutes,
    requireTls = false,
    corsAllowedOrigins = Nil,
    rateLimitPerPrincipalPerSec = 100L,
    rateLimitUploadBytesPerSec = 10L * 1024L * 1024L,
    maxRequestBytes = 5L * 1024L * 1024L * 1024L,
    auditFlushInterval = 2.seconds,
    kmsKeyArn = None,
    clockSkewSeconds = 30L,
  )

  val config: Config[SecurityConfig] =
    (Config.boolean("enabled").withDefault(false) ++
      Config.string("oidc-issuer").optional ++
      Config.string("oidc-audience").optional ++
      Config.duration("jwks-cache-ttl").withDefault(10.minutes) ++
      Config.boolean("require-tls").withDefault(false) ++
      Config.string("cors-allowed-origins").withDefault("") ++
      Config.long("rate-limit-per-principal-per-sec").withDefault(100L) ++
      Config.long("rate-limit-upload-bytes-per-sec").withDefault(10L * 1024L * 1024L) ++
      Config.long("max-request-bytes").withDefault(5L * 1024L * 1024L * 1024L) ++
      Config.duration("audit-flush-interval").withDefault(2.seconds) ++
      Config.string("kms-key-arn").optional ++
      Config.long("clock-skew-seconds").withDefault(30L) ++
      Config.string("dev-shared-secret").optional ++
      Config.string("audit-backend").withDefault("memory"))
      .map {
        case (
              enabled,
              issuer,
              audience,
              jwks,
              tls,
              origins,
              rpsPrincipal,
              bpsUpload,
              maxBytes,
              auditFlush,
              kms,
              skew,
              devSecret,
              auditBackend,
            ) =>
          SecurityConfig(
            enabled = enabled,
            oidcIssuer = issuer,
            oidcAudience = audience,
            jwksCacheTtl = jwks,
            requireTls = tls,
            corsAllowedOrigins =
              if origins.trim.isEmpty then Nil
              else origins.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList,
            rateLimitPerPrincipalPerSec = rpsPrincipal,
            rateLimitUploadBytesPerSec = bpsUpload,
            maxRequestBytes = maxBytes,
            auditFlushInterval = auditFlush,
            kmsKeyArn = kms,
            clockSkewSeconds = skew,
            devSharedSecret = devSecret.filter(_.nonEmpty),
            auditBackend = auditBackend.trim.toLowerCase,
          )
      }
      .nested("security")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, SecurityConfig] =
    ZLayer.fromZIO(ZIO.config(config))

  val default: ULayer[SecurityConfig] =
    ZLayer.succeed(Default)
