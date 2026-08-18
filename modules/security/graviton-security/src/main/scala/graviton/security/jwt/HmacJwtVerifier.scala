package graviton.security.jwt

import graviton.security.{CallerContext, JwtVerifier, SecurityConfig, SecurityError}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.{Base64, UUID}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.util.Try

/**
 * HS256 JWT verifier backed by a shared secret.
 *
 * Intended for two concrete use cases:
 *   1. Local development and smoke-testing — a single secret lets a
 *      developer mint tokens with [[mint]] and call protected endpoints
 *      without standing up an external IdP.
 *   2. Service-to-service deployments where one trusted issuer already
 *      signs tokens with a pre-shared secret.
 *
 * For full OIDC/JWT with JWKS rotation, wire `io.github.arashi01`
 * zio-jwt through [[ZioJwtVerifier]] at assembly time; this verifier
 * stays as the zero-dep fallback.
 */
object HmacJwtVerifier:

  private val HmacAlg    = "HmacSHA256"
  private val urlDecoder = Base64.getUrlDecoder
  private val urlEncoder = Base64.getUrlEncoder.withoutPadding

  def make(
    secret: String,
    clockSkewSeconds: Long,
    expectedIssuer: Option[String],
    expectedAudience: Option[String],
  ): JwtVerifier =
    new JwtVerifier:
      def verify(bearerToken: String, requestId: UUID): IO[SecurityError, CallerContext] =
        for
          parsed <- ZIO.fromEither(parseAndVerify(bearerToken, secret, expectedIssuer, expectedAudience))
          now    <- ZIO.clockWith(_.instant)
          ctx    <- ZIO.fromEither(ClaimMapping.toContext(parsed, requestId, now.getEpochSecond, clockSkewSeconds))
        yield ctx

  def layerFromSecret(secret: String): URLayer[SecurityConfig, JwtVerifier] =
    ZLayer.fromZIO {
      ZIO.serviceWith[SecurityConfig](cfg => make(secret, cfg.clockSkewSeconds, cfg.oidcIssuer, cfg.oidcAudience))
    }

  // ---------- Verification --------------------------------------------------

  private def parseAndVerify(
    token: String,
    secret: String,
    expectedIssuer: Option[String],
    expectedAudience: Option[String],
  ): Either[SecurityError, ClaimMapping.Claims] =
    token.split('.') match
      case Array(headerB64, payloadB64, sigB64) =>
        for
          _      <- verifyAlg(headerB64)
          _      <- verifySignature(s"$headerB64.$payloadB64", sigB64, secret)
          payload = new String(urlDecoder.decode(payloadB64), StandardCharsets.UTF_8)
          claims <- extractClaims(payload, expectedIssuer, expectedAudience)
        yield claims
      case _                                    =>
        Left(SecurityError.Unauthenticated("malformed JWT (expected 3 segments)"))

  private def verifyAlg(headerB64: String): Either[SecurityError, Unit] =
    Try(new String(urlDecoder.decode(headerB64), StandardCharsets.UTF_8)).toEither.left
      .map(err => SecurityError.Unauthenticated(s"bad JWT header: ${err.getMessage}", Some(err)))
      .flatMap { header =>
        if header.contains("\"alg\":\"HS256\"") || header.contains("\"alg\": \"HS256\"") then Right(())
        else Left(SecurityError.Unauthenticated("unsupported JWT alg (HS256 only)"))
      }

  private def verifySignature(signingInput: String, sigB64: String, secret: String): Either[SecurityError, Unit] =
    val computed = hmacSha256(secret, signingInput)
    val provided = Try(urlDecoder.decode(sigB64)).toOption.getOrElse(Array.emptyByteArray)
    if MessageDigest.isEqual(computed, provided) then Right(())
    else Left(SecurityError.Unauthenticated("bad JWT signature"))

  private def hmacSha256(secret: String, data: String): Array[Byte] =
    val mac = Mac.getInstance(HmacAlg)
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HmacAlg))
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))

  private def extractClaims(
    payloadJson: String,
    expectedIssuer: Option[String],
    expectedAudience: Option[String],
  ): Either[SecurityError, ClaimMapping.Claims] =
    payloadJson.fromJson[Json] match
      case Left(err)    => Left(SecurityError.Unauthenticated(s"invalid JWT payload: $err"))
      case Right(value) =>
        val obj = value match
          case Json.Obj(fields) => fields.toMap
          case _                => Map.empty[String, Json]

        def str(key: String): Option[String] =
          obj.get(key).collect { case Json.Str(s) => s }

        def longV(key: String): Option[Long] =
          obj.get(key).collect { case Json.Num(n) => n.longValueExact }

        val audMatches: Either[SecurityError, Unit] =
          expectedAudience match
            case None           => Right(())
            case Some(expected) =>
              obj.get("aud") match
                case Some(Json.Str(s))      =>
                  Either.cond(s == expected, (), SecurityError.Unauthenticated(s"bad audience: $s"))
                case Some(Json.Arr(values)) =>
                  val list = values.collect { case Json.Str(s) => s }
                  Either.cond(list.contains(expected), (), SecurityError.Unauthenticated(s"bad audience: ${list.mkString(",")}"))
                case _                      => Left(SecurityError.Unauthenticated("missing/invalid audience"))

        val issMatches: Either[SecurityError, Unit] =
          expectedIssuer match
            case None      => Right(())
            case Some(iss) =>
              str("iss") match
                case Some(v) if v == iss => Right(())
                case other               =>
                  Left(SecurityError.Unauthenticated(s"bad issuer: ${other.getOrElse("none")}"))

        for
          _ <- issMatches
          _ <- audMatches
        yield ClaimMapping.Claims(
          sub = str("sub"),
          orgId = str("org_id").orElse(str("https://graviton.io/org")),
          principalId = str("principal_id"),
          jti = str("jti"),
          exp = longV("exp"),
          nbf = longV("nbf"),
          scope = str("scope"),
          capsMask = longV("caps"),
        )

  // ---------- Minting -------------------------------------------------------

  def mint(
    secret: String,
    orgId: UUID,
    principalId: UUID,
    capabilities: Long,
    ttlSeconds: Long,
    issuer: Option[String],
    audience: Option[String],
    nowEpochSeconds: Long,
  ): String =
    val iss = issuer.getOrElse("graviton-dev")
    val aud = audience.getOrElse("graviton")
    val jti = UUID.randomUUID().toString
    val exp = nowEpochSeconds + ttlSeconds

    val header       = urlEncoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".getBytes(StandardCharsets.UTF_8))
    val payload      =
      s"""{"iss":${jsonStr(iss)},"sub":${jsonStr(principalId.toString)},"aud":${jsonStr(aud)},""" +
        s""""exp":$exp,"nbf":$nowEpochSeconds,"iat":$nowEpochSeconds,"jti":${jsonStr(jti)},""" +
        s""""org_id":${jsonStr(orgId.toString)},"principal_id":${jsonStr(principalId.toString)},""" +
        s""""caps":$capabilities}"""
    val payloadB64   = urlEncoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
    val signingInput = s"$header.$payloadB64"
    val signature    = urlEncoder.encodeToString(hmacSha256(secret, signingInput))
    s"$signingInput.$signature"

  private def jsonStr(raw: String): String =
    val sb = new StringBuilder("\"")
    raw.foreach {
      case '"'           => sb.append("\\\"")
      case '\\'          => sb.append("\\\\")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c             => sb.append(c)
    }
    sb.append('"')
    sb.toString
