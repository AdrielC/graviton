package graviton.security

import graviton.runtime.constraints.Throttle
import zio.*

import java.util.UUID

/**
 * Per-principal, per-kind token-bucket limiter built on
 * [[graviton.runtime.constraints.Throttle]]. The limiter keeps one bucket
 * per `(orgId, principalId, kind)` tuple and provisions it lazily on first
 * access.
 *
 * Buckets are held in a [[Ref]] map; when a bucket has been idle longer
 * than its refill window it is effectively reset by the underlying throttle
 * (tokens cap at `ratePerSecond`), so a sweeper is unnecessary for the
 * first cut.
 */
trait RateLimiter:
  /** Charges `tokens` tokens for the caller; fails with RateLimited if over budget. */
  def check(kind: RateLimiter.Kind, tokens: Long): IO[SecurityError, Unit]

object RateLimiter:

  enum Kind:
    case Request, UploadBytes, DownloadBytes

  def live: URLayer[SecurityConfig, RateLimiter] =
    ZLayer.fromZIO {
      for
        cfg     <- ZIO.service[SecurityConfig]
        buckets <- Ref.make(Map.empty[Key, Throttle])
      yield new Impl(cfg, buckets)
    }

  private final case class Key(orgId: UUID, principalId: UUID, kind: Kind)

  private final class Impl(cfg: SecurityConfig, buckets: Ref[Map[Key, Throttle]]) extends RateLimiter:

    private val requestLimit  = cfg.rateLimitPerPrincipalPerSec
    private val uploadLimit   = cfg.rateLimitUploadBytesPerSec
    private val downloadLimit = cfg.rateLimitUploadBytesPerSec // same default; separate knob later

    def check(kind: Kind, tokens: Long): IO[SecurityError, Unit] =
      CallerContext.required.flatMap { ctx =>
        val rate = kind match
          case Kind.Request       => requestLimit
          case Kind.UploadBytes   => uploadLimit
          case Kind.DownloadBytes => downloadLimit

        val key = Key(ctx.orgId, ctx.principalId, kind)
        bucketFor(key, rate).flatMap { bucket =>
          ZIO
            .clockWith { clock =>
              bucket
                .take(tokens)
                .provideEnvironment(ZEnvironment[Clock](clock))
            }
            .flatMap { allowed =>
              if allowed then ZIO.unit
              else ZIO.fail(SecurityError.RateLimited(s"rate limit exceeded for $kind"))
            }
        }
      }

    private def bucketFor(key: Key, rate: Long): UIO[Throttle] =
      buckets.get.flatMap { current =>
        current.get(key) match
          case Some(bucket) => ZIO.succeed(bucket)
          case None         =>
            Throttle.make(rate).flatMap { fresh =>
              buckets
                .modify { m =>
                  m.get(key) match
                    case Some(existing) => (existing, m)
                    case None           => (fresh, m.updated(key, fresh))
                }
            }
      }
