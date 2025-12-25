package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.manifest.Manifest
import graviton.runtime.streaming.BlobStreamer
import zio.ZIO
import zio.stream.ZStream

/**
 * Persistence and streaming of blob structure (manifest).
 *
 * This is intentionally "bytes last": implementations should stream structural rows (block refs)
 * and let the block store stream payload bytes.
 */
trait BlobManifestRepo:
  def put(blob: BinaryKey.Blob, manifest: Manifest): ZIO[Any, Throwable, Unit]

  /** Stream block refs in manifest order for read. */
  def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, Throwable, BlobStreamer.BlockRef]
