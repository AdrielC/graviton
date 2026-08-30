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

  /** Bounded registry policy, configured independently of the released security API. */
  final case class RegistryConfig(maximumPrincipals: Int, idleTtl: Duration):
    private[security] def validate: Either[Config.Error, RegistryConfig] =
      for
        _ <- Either.cond(
               maximumPrincipals > 0,
               (),
               Config.Error.InvalidData(Chunk.empty, "rate-limit-maximum-principals must be positive"),
             )
        _ <- Either.cond(
               idleTtl.toNanos > 0L,
               (),
               Config.Error.InvalidData(Chunk.empty, "rate-limit-idle-ttl must be positive"),
             )
      yield this

  object RegistryConfig:
    val Default: RegistryConfig = RegistryConfig(maximumPrincipals = 100000, idleTtl = 10.minutes)

    val config: Config[RegistryConfig] =
      (Config.int("rate-limit-maximum-principals").withDefault(Default.maximumPrincipals) ++
        Config.duration("rate-limit-idle-ttl").withDefault(Default.idleTtl))
        .mapOrFail { case (maximumPrincipals, idleTtl) =>
          RegistryConfig(maximumPrincipals, idleTtl).validate
        }
        .nested("security")
        .nested("graviton")

  def live: URLayer[SecurityConfig, RateLimiter] =
    bounded(RegistryConfig.Default)

  def configured(registryConfig: RegistryConfig): ZLayer[SecurityConfig, Config.Error, RateLimiter] =
    ZLayer.fromZIO(ZIO.fromEither(registryConfig.validate)) >>> bounded(registryConfig)

  private def bounded(registryConfig: RegistryConfig): URLayer[SecurityConfig, RateLimiter] =
    ZLayer.fromZIO {
      for
        cfg    <- ZIO.service[SecurityConfig]
        shards <- ZIO.foreach(0 until shardCount(registryConfig.maximumPrincipals)) { index =>
                    Ref.Synchronized
                      .make(Map.empty[PrincipalKey, Entry])
                      .map(state => Shard(state, shardCapacity(registryConfig.maximumPrincipals, index)))
                  }
      yield new BoundedImpl(cfg, registryConfig, shards)
    }

  /** Retained for binary compatibility with v0.7.0. New code uses PrincipalKey. */
  private final case class Key(orgId: UUID, principalId: UUID, kind: Kind)

  /** Retained for binary compatibility with v0.7.0. */
  private final class Impl(cfg: SecurityConfig, buckets: Ref[Map[Key, Throttle]]) extends RateLimiter:

    private val requestLimit  = cfg.rateLimitPerPrincipalPerSec
    private val uploadLimit   = cfg.rateLimitUploadBytesPerSec
    private val downloadLimit = cfg.rateLimitDownloadBytesPerSec

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
              buckets.modify { entries =>
                entries.get(key) match
                  case Some(existing) => (existing, entries)
                  case None           => (fresh, entries.updated(key, fresh))
              }
            }
      }

  private final case class PrincipalKey(orgId: UUID, principalId: UUID)

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

  private final case class Shard(state: Ref.Synchronized[Map[PrincipalKey, Entry]], capacity: Int)

  private final class BoundedImpl(cfg: SecurityConfig, registryConfig: RegistryConfig, shards: IndexedSeq[Shard]) extends RateLimiter:

    private val requestLimit  = cfg.rateLimitPerPrincipalPerSec
    private val uploadLimit   = cfg.rateLimitUploadBytesPerSec
    private val downloadLimit = cfg.rateLimitDownloadBytesPerSec

    def check(kind: Kind, tokens: Long): IO[SecurityError, Unit] =
      ZIO.fail(SecurityError.RateLimited("rate-limit charge must be positive")).unless(tokens > 0L) *>
        CallerContext.required.flatMap { ctx =>
          val key = PrincipalKey(ctx.orgId, ctx.principalId)
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

    private def bucketFor(key: PrincipalKey, kind: Kind): IO[SecurityError, Throttle] =
      Clock.nanoTime.flatMap { now =>
        val shard = shards(shardIndex(key, shards.length))
        shard.state.modifyZIO { current =>
          current.get(key) match
            case Some(entry) =>
              val touched = entry.copy(lastAccessNanos = now)
              ZIO.succeed(touched.bucket(kind) -> current.updated(key, touched))
            case None        =>
              val expired  = current.iterator
                .filter { case (_, entry) => elapsedSince(entry.lastAccessNanos, now) >= registryConfig.idleTtl.toNanos }
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

  private def shardIndex(key: PrincipalKey, shards: Int): Int =
    java.lang.Math.floorMod(key.hashCode, shards)
