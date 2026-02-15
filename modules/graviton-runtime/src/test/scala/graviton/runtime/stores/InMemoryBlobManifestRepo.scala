package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.runtime.streaming.BlobStreamer
import zio.*
import zio.stream.ZStream

/**
 * TEST-ONLY in-memory manifest repository.
 *
 * Shared across test suites that need a `BlobManifestRepo` without a database.
 */
final class InMemoryBlobManifestRepo private (
  ref: Ref[Map[BinaryKey.Blob, Manifest]]
) extends BlobManifestRepo:

  override def put(blob: BinaryKey.Blob, manifest: Manifest): ZIO[Any, Throwable, Unit] =
    ref.update(_.updated(blob, manifest)).unit

  override def get(blob: BinaryKey.Blob): ZIO[Any, Throwable, Option[Manifest]] =
    ref.get.map(_.get(blob))

  override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, Throwable, BlobStreamer.BlockRef] =
    ZStream.fromZIO(ref.get.map(_.get(blob))).flatMap {
      case None    =>
        ZStream.fail(new NoSuchElementException(s"Missing manifest for ${blob.bits.digest.hex.value}"))
      case Some(m) =>
        ZStream.fromIterable(
          m.entries.zipWithIndex.collect { case (ManifestEntry(b: BinaryKey.Block, _, _), idx) =>
            BlobStreamer.BlockRef(idx.toLong, b)
          }
        )
    }

  override def delete(blob: BinaryKey.Blob): ZIO[Any, Throwable, Boolean] =
    ref.modify { map =>
      if map.contains(blob) then (true, map - blob)
      else (false, map)
    }

  /** List all stored blob keys. */
  def keys: ZIO[Any, Nothing, Set[BinaryKey.Blob]] =
    ref.get.map(_.keySet)

  /** Get the raw map snapshot for assertions. */
  def snapshot: ZIO[Any, Nothing, Map[BinaryKey.Blob, Manifest]] =
    ref.get

object InMemoryBlobManifestRepo:
  def make: UIO[InMemoryBlobManifestRepo] =
    Ref.make(Map.empty[BinaryKey.Blob, Manifest]).map(new InMemoryBlobManifestRepo(_))
