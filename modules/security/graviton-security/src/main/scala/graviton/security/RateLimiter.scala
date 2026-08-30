package graviton.security

import graviton.runtime.constraints.Throttle
import zio.*

import java.util.UUID

/**
 * Per-principal, per-kind token-bucket limiter built on
 * [[graviton.runtime.constraints.Throttle]]. The limiter keeps one bounded
 * entry per `(orgId, principalId)` with independent request, upload, and
 * download buckets. At capacity it evicts only entries idle past the
 * configured TTL. If every entry is active, it fails closed instead of
 * growing without bound or silently resetting an existing caller's budget.
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
        cfg    <- ZIO.service[SecurityConfig]
        shards <- ZIO.foreach(0 until shardCount(cfg.rateLimitMaximumPrincipals)) { index =>
                    Ref.Synchronized
                      .make(Map.empty[Key, Entry])
                      .map(state => Shard(state, shardCapacity(cfg.rateLimitMaximumPrincipals, index)))
                  }
      yield new Impl(cfg, shards)
    }

  private final case class Key(orgId: UUID, principalId: UUID)

  private final case class Entry(
    request: Throttle,
    upload: Throttle,
    download: Throttle,
    lastAccessNanos: Long,
  ):
    def bucket(kind: Kind): Throttle = kind match
      case Kind.Request       => request
      case Kind.UploadBytes   => upload
      case Kind.DownloadBytes => download

  private final case class Shard(state: Ref.Synchronized[Map[Key, Entry]], capacity: Int)

  private final class Impl(cfg: SecurityConfig, shards: IndexedSeq[Shard]) extends RateLimiter:

    private val requestLimit  = cfg.rateLimitPerPrincipalPerSec
    private val uploadLimit   = cfg.rateLimitUploadBytesPerSec
    private val downloadLimit = cfg.rateLimitDownloadBytesPerSec

    def check(kind: Kind, tokens: Long): IO[SecurityError, Unit] =
      ZIO.fail(SecurityError.RateLimited("rate-limit charge must be positive")).unless(tokens > 0L) *>
        CallerContext.required.flatMap { ctx =>
          val key = Key(ctx.orgId, ctx.principalId)
          bucketFor(key, kind).flatMap { bucket =>
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

    private def bucketFor(key: Key, kind: Kind): IO[SecurityError, Throttle] =
      Clock.nanoTime.flatMap { now =>
        val shard = shards(shardIndex(key, shards.length))
        shard.state.modifyZIO { current =>
          current.get(key) match
            case Some(entry) =>
              val touched = entry.copy(lastAccessNanos = now)
              ZIO.succeed(touched.bucket(kind) -> current.updated(key, touched))
            case None        =>
              val expired  = current.iterator
                .filter { case (_, entry) => elapsedSince(entry.lastAccessNanos, now) >= cfg.rateLimitIdleTtl.toNanos }
                .minByOption(_._2.lastAccessNanos)
                .map(_._1)
              val withRoom =
                if current.size < shard.capacity then Some(current)
                else expired.map(current.removed)
              withRoom match
                case None          =>
                  ZIO.fail(SecurityError.RateLimited("rate limiter principal capacity is exhausted"))
                case Some(entries) =>
                  for
                    request  <- Throttle.make(requestLimit)
                    upload   <- Throttle.make(uploadLimit)
                    download <- Throttle.make(downloadLimit)
                    created   = Entry(request, upload, download, now)
                  yield created.bucket(kind) -> entries.updated(key, created)
        }
      }

    private def elapsedSince(start: Long, end: Long): Long =
      if end <= start then 0L else end - start

  private def shardCount(maximumEntries: Int): Int = math.min(64, maximumEntries)

  private def shardCapacity(maximumEntries: Int, index: Int): Int =
    val shards    = shardCount(maximumEntries)
    val base      = maximumEntries / shards
    val remainder = maximumEntries % shards
    base + (if index < remainder then 1 else 0)

  private def shardIndex(key: Key, shards: Int): Int =
    java.lang.Math.floorMod(key.hashCode, shards)
