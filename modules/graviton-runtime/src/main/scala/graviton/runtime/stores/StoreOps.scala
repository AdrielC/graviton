package graviton.runtime.stores

import graviton.core.GravitonError
import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlobWritePlan, BlobWriteResult}
import zio.*
import zio.stream.*

import java.nio.file.Path

/**
 * Extension methods for store services that provide typed-error variants
 * and convenience helpers.
 *
 * These extensions layer over the existing `Throwable`-based APIs so that
 * callers who want `GravitonError` typed channels can opt in without
 * changing the core trait signatures.
 */
object StoreOps:

  extension (store: BlobStore)

    /**
     * Ingest a file from the local filesystem.
     *
     * Streams the file bytes through the blob store's `put` sink and returns
     * the write result. The stream is fully consumed; no leftover handling
     * is needed for whole-file ingest.
     */
    def insertFile(
      path: Path,
      plan: BlobWritePlan = BlobWritePlan(),
    ): ZIO[Any, Throwable, BlobWriteResult] =
      ZStream
        .fromFile(path.toFile, chunkSize = 64 * 1024)
        .run(store.put(plan))

    /**
     * Ingest a file with errors mapped to `GravitonError`.
     */
    def insertFileTyped(
      path: Path,
      plan: BlobWritePlan = BlobWritePlan(),
    ): ZIO[Any, GravitonError, BlobWriteResult] =
      insertFile(path, plan).mapError(GravitonError.fromThrowable)

    /**
     * Retrieve blob bytes with errors mapped to `GravitonError`.
     */
    def getTyped(key: BinaryKey): ZStream[Any, GravitonError, Byte] =
      store.get(key).mapError(GravitonError.fromThrowable)

    /**
     * Delete a blob with errors mapped to `GravitonError`.
     */
    def deleteTyped(key: BinaryKey): ZIO[Any, GravitonError, Unit] =
      store.delete(key).mapError(GravitonError.fromThrowable)

  extension (store: BlockStore)

    /**
     * Retrieve block bytes with errors mapped to `GravitonError`.
     */
    def getTyped(key: BinaryKey.Block): ZStream[Any, GravitonError, Byte] =
      store.get(key).mapError(GravitonError.fromThrowable)

    /**
     * Check block existence with errors mapped to `GravitonError`.
     */
    def existsTyped(key: BinaryKey.Block): ZIO[Any, GravitonError, Boolean] =
      store.exists(key).mapError(GravitonError.fromThrowable)
