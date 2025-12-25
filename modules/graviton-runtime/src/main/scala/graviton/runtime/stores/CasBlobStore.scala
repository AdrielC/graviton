package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.core.ranges.Span
import graviton.core.model.Block.*
import graviton.runtime.model.{BlobStat, BlobWritePlan, BlobWriteResult, CanonicalBlock}
import graviton.runtime.streaming.BlobStreamer
import zio.*
import zio.stream.*

/**
 * Streaming-first CAS blob store:
 * - chunk bytes into bounded blocks (never empty)
 * - store blocks by CAS key (via [[BlockStore]])
 * - build and persist manifest (via [[BlobManifestRepo]])
 * - serve reads by streaming refs from DB and bytes from the block store
 */
final class CasBlobStore(
  blockStore: BlockStore,
  manifests: BlobManifestRepo,
  streamerConfig: BlobStreamer.Config = BlobStreamer.Config(),
) extends BlobStore:

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink.unwrapScoped {
      for
        chunker    <- graviton.streams.Chunker.current.get
        blobHasher <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
        totalBytes <- Ref.make(0L)

        // Stage 1: enqueue incoming bytes (bounded).
        inputQ <- Queue.bounded[Take[Throwable, Byte]](math.max(1, streamerConfig.windowRefs))

        // Stage 2: canonical blocks to be persisted (bounded).
        blocksQ <- Queue.bounded[Take[Throwable, CanonicalBlock]](math.max(1, streamerConfig.windowRefs))

        // Stage 3: block store batch result (manifest, stored statuses, etc).
        batchDone <- Promise.make[Throwable, graviton.runtime.model.BlockBatchResult]

        // Persist blocks as they're produced.
        _ <-
          (ZStream
            .fromQueue(blocksQ)
            .flattenTake
            .run(blockStore.putBlocks())
            .intoPromise(batchDone))
            .forkScoped

        // Run chunker on the incoming byte stream and emit canonical blocks.
        _ <-
          (ZStream
            .fromQueue(inputQ)
            .flattenTake
            .via(chunker.pipeline)
            .mapZIO { block =>
              val payload = block.bytes
              for
                hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
                _       = hasher.update(payload.toArray)
                digest <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
                bits   <- ZIO
                            .fromEither(KeyBits.create(hasher.algo, digest, payload.length.toLong))
                            .mapError(msg => new IllegalArgumentException(msg))
                key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(msg => new IllegalArgumentException(msg))
                canon  <- ZIO
                            .fromEither(CanonicalBlock.make(key, payload, BinaryAttributes.empty))
                            .mapError(msg => new IllegalArgumentException(msg))
              yield canon
            }
            .runForeach(canon => blocksQ.offer(Take.single(canon)).unit)
            .catchAll(err => blocksQ.offer(Take.fail(err)).unit)
            .ensuring(blocksQ.offer(Take.end).ignore))
            .forkScoped
      yield ZSink
        .foldLeftChunksZIO[Any, Throwable, Byte, Unit](()) { (_, in) =>
          ZIO.attempt {
            blobHasher.update(in.toArray)
          } *>
            totalBytes.update(_ + in.length.toLong) *>
            inputQ.offer(Take.chunk(in)).unit
        }
        .mapZIO { _ =>
          for
            _ <- ZIO
                   .fail(new IllegalArgumentException("Empty blobs are not supported (size must be > 0)"))
                   .whenZIO(totalBytes.get.map(_ <= 0L))
            _ <- inputQ.offer(Take.end).ignore

            batch <- batchDone.await

            digest <- ZIO.fromEither(blobHasher.digest).mapError(msg => new IllegalArgumentException(msg))
            size   <- totalBytes.get
            bits   <- ZIO
                        .fromEither(KeyBits.create(blobHasher.algo, digest, size))
                        .mapError(msg => new IllegalArgumentException(msg))
            blob   <- ZIO.fromEither(BinaryKey.blob(bits)).mapError(msg => new IllegalArgumentException(msg))

            // Convert the runtime block manifest into the generic manifest format.
            entries  <- ZIO
                          .foreach(batch.manifest.entries) { e =>
                            val start = e.offset.value
                            val end   = start + e.size.value.toLong - 1L
                            ZIO
                              .fromEither(Span.make(start, end))
                              .mapError(msg => new IllegalArgumentException(msg))
                              .map(span => ManifestEntry(e.key, span, Map.empty))
                          }
                          .map(_.toList)
            manifest <- ZIO.fromEither(Manifest.fromEntries(entries)).mapError(msg => new IllegalArgumentException(msg))

            _ <- manifests.put(blob, manifest)

            locator = plan.locatorHint.getOrElse(graviton.core.locator.BlobLocator("cas", "manifest", blob.bits.digest.hex.value))
            attrs   = plan.attributes
          yield BlobWriteResult(blob, locator, attrs)
        }
        .ignoreLeftover
    }

  override def get(key: BinaryKey): ZStream[Any, Throwable, Byte] =
    key match
      case blob: BinaryKey.Blob =>
        BlobStreamer.streamBlob(manifests.streamBlockRefs(blob), blockStore, streamerConfig)
      case other                =>
        ZStream.fail(new UnsupportedOperationException(s"CasBlobStore.get only supports blob keys, got $other"))

  override def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]] =
    ZIO.succeed(None)

  override def delete(key: BinaryKey): ZIO[Any, Throwable, Unit] =
    ZIO.fail(new UnsupportedOperationException("CasBlobStore.delete is not implemented yet"))

object CasBlobStore:
  val layer: ZLayer[BlockStore & BlobManifestRepo, Nothing, BlobStore] =
    ZLayer.fromFunction((bs: BlockStore, repo: BlobManifestRepo) => new CasBlobStore(bs, repo))
