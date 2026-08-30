package graviton.backend.pg

import graviton.core.locator.BlobLocator
import graviton.core.ranges.{RangeSet, Span}
import graviton.core.types.BlobOffset
import graviton.runtime.indexes.RangeTracker
import graviton.runtime.kv.{KeyValueStore, KvKey, KvValue}
import graviton.runtime.stores.{StoreError, StoreOperation}
import zio.{IO, Ref, ZIO, ZLayer}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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

  override def current(locator: BlobLocator): IO[StoreError, RangeSet[BlobOffset]] =
    cache.modifyZIO { m =>
      m.get(locator) match
        case Some(rs) => ZIO.succeed((rs, m))
        case None     => load(locator).map(rs => (rs, m.updated(locator, rs)))
    }

  override def merge(locator: BlobLocator, span: Span[BlobOffset]): IO[StoreError, RangeSet[BlobOffset]] =
    cache.modifyZIO { entries =>
      val existing = entries.get(locator).fold(load(locator))(ZIO.succeed(_))
      existing.flatMap { ranges =>
        val merged = ranges.add(span)
        for
          encoded <- ZIO
                       .fromEither(PgRangeTracker.encode(merged))
                       .mapError(StoreError.InvalidInput(StoreOperation.MergeRanges, _))
          // Keep serialization and persistence inside the synchronized update.
          // Otherwise two fibers can write their snapshots out of order, or a
          // failed write can leave the cache claiming durability it never got.
          _       <- kv.put(PgRangeTracker.key(locator), encoded)
        yield (merged, entries.updated(locator, merged))
      }
    }

  private def load(locator: BlobLocator): IO[StoreError, RangeSet[BlobOffset]] =
    kv.get(PgRangeTracker.key(locator)).flatMap {
      case None        => ZIO.succeed(RangeSet.empty[BlobOffset])
      case Some(bytes) =>
        PgRangeTracker.decode(bytes) match
          case Right(rs)     => ZIO.succeed(rs)
          case Left(message) =>
            ZIO.fail(StoreError.CorruptData(StoreOperation.ReadRanges, s"Corrupt persisted range set for '${locator.render}': $message"))
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

  private def key(locator: BlobLocator): KvKey =
    val locBytes = locator.render.getBytes(StandardCharsets.UTF_8)
    val digest   = MessageDigest.getInstance("SHA-256").digest(locBytes)
    val hex      = digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
    KvKey.applyUnsafe(Prefix + hex)

  private def encode(set: RangeSet[BlobOffset]): Either[String, KvValue] =
    val spans  = set.spans
    val n      = spans.length
    val length = 8L + n.toLong * 16L
    if length > KvValue.MaxBytes then Left(s"Range set encoding exceeds ${KvValue.MaxBytes} bytes")
    else
      val buf = ByteBuffer.allocate(length.toInt)
      buf.putInt(Magic)
      buf.putInt(n)
      spans.foreach { s =>
        buf.putLong(s.startInclusive.value)
        buf.putLong(s.endInclusive.value)
      }
      KvValue.fromArray(buf.array())

  private def decode(bytes: KvValue): Either[String, RangeSet[BlobOffset]] =
    val raw = bytes.toArray
    if raw.length < 8 then Left("RangeSet payload too short")
    else
      val buf = ByteBuffer.wrap(raw)
      val mg  = buf.getInt()
      if mg != Magic then Left("RangeSet payload has invalid magic")
      else
        val n = buf.getInt()
        if n < 0 then Left("RangeSet payload has negative span count")
        else if raw.length.toLong != 8L + n.toLong * 16L then Left("RangeSet payload length mismatch")
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
