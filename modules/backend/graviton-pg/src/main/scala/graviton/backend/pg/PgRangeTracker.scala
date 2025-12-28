package graviton.backend.pg

import graviton.core.locator.BlobLocator
import graviton.core.ranges.{RangeSet, Span}
import graviton.core.types.BlobOffset
import graviton.runtime.indexes.RangeTracker
import graviton.runtime.kv.KeyValueStore
import zio.{Ref, ZIO, ZLayer}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Range tracking for partial blob materialization.
 *
 * This is intentionally implemented as a **local write-through cache** (per-process) backed by a
 * [[graviton.runtime.kv.KeyValueStore]]:
 * - `current` is served from memory when possible and otherwise loaded from the KV store.
 * - `merge` updates the in-memory cache atomically and writes the merged set back to KV.
 *
 * The point is to avoid repeatedly recomputing / refetching the same spans during a session while
 * still having a persistence hook for restarts.
 *
 * Note: the underlying KV implementations are pluggable (e.g. Postgres / Rocks). This class does
 * not assume a specific persistence scheme beyond `get/put/delete`.
 */
final class PgRangeTracker private (
  kv: KeyValueStore,
  cache: Ref.Synchronized[Map[BlobLocator, RangeSet[BlobOffset]]],
) extends RangeTracker:

  override def current(locator: BlobLocator): ZIO[Any, Throwable, RangeSet[BlobOffset]] =
    cache.modifyZIO { m =>
      m.get(locator) match
        case Some(rs) => ZIO.succeed((rs, m))
        case None     =>
          load(locator).either.map {
            case Right(rs) => (rs, m.updated(locator, rs))
            case Left(_)   => (RangeSet.empty[BlobOffset], m) // don't poison cache on transient KV failure
          }
    }

  override def merge(locator: BlobLocator, span: Span[BlobOffset]): ZIO[Any, Throwable, RangeSet[BlobOffset]] =
    for
      // Ensure we try to populate from KV at most once per locator.
      existing <- current(locator)
      merged   <- cache.modify { m =>
                    val next = m.getOrElse(locator, existing).add(span)
                    (next, m.updated(locator, next))
                  }
      // Best-effort persistence: the in-process cache remains correct even if KV is temporarily unavailable.
      _        <- kv.put(PgRangeTracker.key(locator), PgRangeTracker.encode(merged)).catchAll(_ => ZIO.unit)
    yield merged

  private def load(locator: BlobLocator): ZIO[Any, Throwable, RangeSet[BlobOffset]] =
    kv.get(PgRangeTracker.key(locator)).flatMap {
      case None        => ZIO.succeed(RangeSet.empty[BlobOffset])
      case Some(bytes) =>
        PgRangeTracker.decode(bytes) match
          case Right(rs) => ZIO.succeed(rs)
          case Left(_)   =>
            // Corrupt payload: drop it and fail closed to empty.
            kv.delete(PgRangeTracker.key(locator)).ignore.as(RangeSet.empty[BlobOffset])
    }

object PgRangeTracker:

  /**
   * In-process constructor (starts with an empty cache).
   *
   * The cache is per-instance (typically per-layer / per-process).
   */
  def make(kv: KeyValueStore): ZIO[Any, Nothing, PgRangeTracker] =
    Ref.Synchronized
      .make(Map.empty[BlobLocator, RangeSet[BlobOffset]])
      .map(ref => new PgRangeTracker(kv, ref))

  val live: ZLayer[KeyValueStore, Nothing, PgRangeTracker] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[KeyValueStore](make))

  private val Prefix = "range-tracker/v1/"
  private val Magic  = 0x47525452 // "GRTR"

  private def key(locator: BlobLocator): String =
    val locBytes = locator.render.getBytes(StandardCharsets.UTF_8)
    val enc      = Base64.getUrlEncoder.withoutPadding().encodeToString(locBytes)
    Prefix + enc

  private def encode(set: RangeSet[BlobOffset]): Array[Byte] =
    val spans = set.spans
    val n     = spans.length
    val buf   = ByteBuffer.allocate(4 + 4 + n * 16) // magic + count + spans
    buf.putInt(Magic)
    buf.putInt(n)
    spans.foreach { s =>
      buf.putLong(s.startInclusive.value)
      buf.putLong(s.endInclusive.value)
    }
    buf.array()

  private def decode(bytes: Array[Byte]): Either[String, RangeSet[BlobOffset]] =
    if bytes.length < 8 then Left("RangeSet payload too short")
    else
      val buf = ByteBuffer.wrap(bytes)
      val mg  = buf.getInt()
      if mg != Magic then Left("RangeSet payload has invalid magic")
      else
        val n = buf.getInt()
        if n < 0 then Left("RangeSet payload has negative span count")
        else if bytes.length != 8 + n * 16 then Left("RangeSet payload length mismatch")
        else
          val spans =
            (0 until n).iterator.map { _ =>
              val start0 = buf.getLong()
              val end0   = buf.getLong()
              for
                start <- BlobOffset.either(start0)
                end   <- BlobOffset.either(end0)
                span  <- Span.make(start, end)
              yield span
            }.toList

          spans
            .foldLeft[Either[String, List[Span[BlobOffset]]]](Right(Nil)) { (acc, next) =>
              acc.flatMap(xs => next.map(xs :+ _))
            }
            .map(RangeSet.fromSpans(_))
