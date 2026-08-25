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
  oidcJwksUri: Option[String],
  jwksCacheTtl: Duration,
  requireTls: Boolean,
  trustProxyHeaders: Boolean,
  corsAllowedOrigins: List[String],
  rateLimitPerPrincipalPerSec: Long,
  rateLimitUploadBytesPerSec: Long,
  rateLimitDownloadBytesPerSec: Long,
  maxRequestBytes: Long,
  auditFlushInterval: Duration,
  kmsKeyArn: Option[String],
  clockSkewSeconds: Long,
  devSharedSecret: Option[String] = None,
  auditBackend: String = "memory",
  authorizationBackend: String = "token",
):
  /** Fail-fast validation used by Main at startup when security is enabled. */
  def validate: Either[String, SecurityConfig] =
    if !enabled then Right(this)
    else
      for
        _ <- oidcIssuer.toRight("GRAVITON_SECURITY_OIDC_ISSUER is required when security is enabled")
        _ <- oidcAudience.toRight("GRAVITON_SECURITY_OIDC_AUDIENCE is required when security is enabled")
        _ <- Either.cond(rateLimitPerPrincipalPerSec > 0L, (), "GRAVITON_SECURITY_RATE_LIMIT_PER_PRINCIPAL_PER_SEC must be positive")
        _ <- Either.cond(rateLimitUploadBytesPerSec > 0L, (), "GRAVITON_SECURITY_RATE_LIMIT_UPLOAD_BYTES_PER_SEC must be positive")
        _ <- Either.cond(rateLimitDownloadBytesPerSec > 0L, (), "GRAVITON_SECURITY_RATE_LIMIT_DOWNLOAD_BYTES_PER_SEC must be positive")
        _ <- Either.cond(maxRequestBytes > 0L, (), "GRAVITON_SECURITY_MAX_REQUEST_BYTES must be positive")
        _ <- Either.cond(
               Set("token", "jdbc").contains(authorizationBackend),
               (),
               "GRAVITON_SECURITY_AUTHORIZATION_BACKEND must be token or jdbc",
             )
        _ <-
          if devSharedSecret.nonEmpty then Right(())
          else
            oidcJwksUri
              .toRight("GRAVITON_SECURITY_OIDC_JWKS_URI is required when security is enabled without a dev shared secret")
              .flatMap { raw =>
                scala.util
                  .Try(java.net.URI.create(raw))
                  .toEither
                  .left
                  .map(_ => "GRAVITON_SECURITY_OIDC_JWKS_URI must be an absolute HTTPS URI")
                  .flatMap(uri =>
                    Either.cond(
                      uri.isAbsolute && uri.getScheme.equalsIgnoreCase("https"),
                      (),
                      "GRAVITON_SECURITY_OIDC_JWKS_URI must be an absolute HTTPS URI",
                    )
                  )
              }
      yield this

object SecurityConfig:

  val Default: SecurityConfig = SecurityConfig(
    enabled = false,
    oidcIssuer = None,
    oidcAudience = None,
    oidcJwksUri = None,
    jwksCacheTtl = 10.minutes,
    requireTls = false,
    trustProxyHeaders = false,
    corsAllowedOrigins = Nil,
    rateLimitPerPrincipalPerSec = 100L,
    rateLimitUploadBytesPerSec = 10L * 1024L * 1024L,
    rateLimitDownloadBytesPerSec = 50L * 1024L * 1024L,
    maxRequestBytes = 5L * 1024L * 1024L * 1024L,
    auditFlushInterval = 2.seconds,
    kmsKeyArn = None,
    clockSkewSeconds = 30L,
  )

  val config: Config[SecurityConfig] =
    (Config.boolean("enabled").withDefault(false) ++
      Config.string("oidc-issuer").optional ++
      Config.string("oidc-audience").optional ++
      Config.string("oidc-jwks-uri").optional ++
      Config.duration("jwks-cache-ttl").withDefault(10.minutes) ++
      Config.boolean("require-tls").withDefault(false) ++
      Config.boolean("trust-proxy-headers").withDefault(false) ++
      Config.string("cors-allowed-origins").withDefault("") ++
      Config.long("rate-limit-per-principal-per-sec").withDefault(100L) ++
      Config.long("rate-limit-upload-bytes-per-sec").withDefault(10L * 1024L * 1024L) ++
      Config.long("rate-limit-download-bytes-per-sec").withDefault(50L * 1024L * 1024L) ++
      Config.long("max-request-bytes").withDefault(5L * 1024L * 1024L * 1024L) ++
      Config.duration("audit-flush-interval").withDefault(2.seconds) ++
      Config.string("kms-key-arn").optional ++
      Config.long("clock-skew-seconds").withDefault(30L) ++
      Config.string("dev-shared-secret").optional ++
      Config.string("audit-backend").withDefault("memory") ++
      Config.string("authorization-backend").withDefault("token"))
      .map {
        case (
              enabled,
              issuer,
              audience,
              jwksUri,
              jwks,
              tls,
              trustProxy,
              origins,
              rpsPrincipal,
              bpsUpload,
              bpsDownload,
              maxBytes,
              auditFlush,
              kms,
              skew,
              devSecret,
              auditBackend,
              authorizationBackend,
            ) =>
          SecurityConfig(
            enabled = enabled,
            oidcIssuer = issuer,
            oidcAudience = audience,
            oidcJwksUri = jwksUri,
            jwksCacheTtl = jwks,
            requireTls = tls,
            trustProxyHeaders = trustProxy,
            corsAllowedOrigins =
              if origins.trim.isEmpty then Nil
              else origins.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList,
            rateLimitPerPrincipalPerSec = rpsPrincipal,
            rateLimitUploadBytesPerSec = bpsUpload,
            rateLimitDownloadBytesPerSec = bpsDownload,
            maxRequestBytes = maxBytes,
            auditFlushInterval = auditFlush,
            kmsKeyArn = kms,
            clockSkewSeconds = skew,
            devSharedSecret = devSecret.filter(_.nonEmpty),
            auditBackend = auditBackend.trim.toLowerCase,
            authorizationBackend = authorizationBackend.trim.toLowerCase,
          )
      }
      .nested("security")
      .nested("graviton")

  val layer: ZLayer[Any, Config.Error, SecurityConfig] =
    ZLayer.fromZIO(ZIO.config(config))

  val default: ULayer[SecurityConfig] =
    ZLayer.succeed(Default)
