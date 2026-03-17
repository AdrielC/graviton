package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.manifest.Manifest
import graviton.runtime.streaming.BlobStreamer
import zio.ZIO
import zio.stream.ZStream

import java.time.Instant

/**
 * A manifest paired with the timestamp at which it was persisted.
 *
 * The `ingestedAt` value is captured via `Clock.instant` during [[BlobManifestRepo.put]]
 * so that [[BlobStore.stat]] can return an honest `lastModified` timestamp.
 */
final case class StoredManifest(manifest: Manifest, ingestedAt: Instant)

/**
 * Persistence and streaming of blob structure (manifest).
 *
 * This is intentionally "bytes last": implementations should stream structural rows (block refs)
 * and let the block store stream payload bytes.
 */
trait BlobManifestRepo:
  def put(blob: BinaryKey.Blob, manifest: Manifest, ingestedAt: Instant): ZIO[Any, Throwable, Unit]

  /** Retrieve the manifest and its ingestion timestamp for a blob, if it exists. */
  def get(blob: BinaryKey.Blob): ZIO[Any, Throwable, Option[StoredManifest]]

  /** Stream block refs in manifest order for read. */
  def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, Throwable, BlobStreamer.BlockRef]

  /** Remove the manifest entry for a blob. Returns true if it existed. */
  def delete(blob: BinaryKey.Blob): ZIO[Any, Throwable, Boolean] =
    ZIO.fail(new UnsupportedOperationException("BlobManifestRepo.delete is not implemented by this backend"))

object BlobManifestRepo:
  val service: ZIO[BlobManifestRepo, Nothing, BlobManifestRepo] = ZIO.service[BlobManifestRepo]
