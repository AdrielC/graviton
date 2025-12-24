package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.core.ranges.Span
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
        blobHasher <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
        totalBytes <- Ref.make(0L)

        // Feed canonical blocks to the block store incrementally.
        blocksQ   <- Queue.bounded[Take[Throwable, CanonicalBlock]](math.max(1, streamerConfig.windowRefs))
        batchDone <- Promise.make[Throwable, graviton.runtime.model.BlockBatchResult]

        _ <-
          (ZStream
            .fromQueue(blocksQ)
            .flattenTake
            .run(blockStore.putBlocks())
            .intoPromise(batchDone))
            .forkScoped
      yield ZSink
        .foldLeftChunksZIO[Any, Throwable, Byte, Chunk[Byte]](Chunk.empty) { (carry, in) =>
          // Maintain a bounded carry buffer so we never need to buffer the whole blob.
          val combined = if carry.isEmpty then in else carry ++ in
          val max      = graviton.core.types.MaxBlockBytes

          ZIO.attempt {
            // Update blob hasher on raw bytes.
            val arr = in.toArray
            blobHasher.update(arr)
          } *>
            totalBytes.update(_ + in.length.toLong) *>
            ZIO
              .succeed {
                val fullBlocks                      = combined.length / max
                val emitBytes                       = combined.take(fullBlocks * max)
                val nextCarry                       = combined.drop(fullBlocks * max)
                val blocksChunk: Chunk[Chunk[Byte]] =
                  if emitBytes.isEmpty then Chunk.empty
                  else Chunk.fromIterable(emitBytes.grouped(max).toList)
                (blocksChunk, nextCarry)
              }
              .flatMap { case (blocks, nextCarry) =>
                ZIO
                  .foreach(blocks) { bytes =>
                    // Apply the selected chunker program to the already-bounded bytes if caller asked for it.
                    // For v1, we use fixed chunking from the bounded buffer.
                    val payload = bytes
                    for
                      blockHasher <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
                      _            = blockHasher.update(payload.toArray)
                      digest      <- ZIO.fromEither(blockHasher.digest).mapError(msg => new IllegalArgumentException(msg))
                      bits        <- ZIO
                                       .fromEither(KeyBits.create(blockHasher.algo, digest, payload.length.toLong))
                                       .mapError(msg => new IllegalArgumentException(msg))
                      key         <- ZIO.fromEither(BinaryKey.block(bits)).mapError(msg => new IllegalArgumentException(msg))
                      canonical   <- ZIO
                                       .fromEither(CanonicalBlock.make(key, payload, BinaryAttributes.empty))
                                       .mapError(msg => new IllegalArgumentException(msg))
                      _           <- blocksQ.offer(Take.single(canonical))
                    yield ()
                  }
                  .as(nextCarry)
              }
        }
        .mapZIO { finalCarry =>
          // Flush remainder as final block (must be non-empty).
          (for
            _ <- ZIO
                   .fail(new IllegalArgumentException("Empty blobs are not supported (size must be > 0)"))
                   .whenZIO(totalBytes.get.map(_ <= 0L))

            _ <-
              ZIO.when(finalCarry.nonEmpty) {
                for
                  blockHasher <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
                  _            = blockHasher.update(finalCarry.toArray)
                  digest      <- ZIO.fromEither(blockHasher.digest).mapError(msg => new IllegalArgumentException(msg))
                  bits        <- ZIO
                                   .fromEither(KeyBits.create(blockHasher.algo, digest, finalCarry.length.toLong))
                                   .mapError(msg => new IllegalArgumentException(msg))
                  key         <- ZIO.fromEither(BinaryKey.block(bits)).mapError(msg => new IllegalArgumentException(msg))
                  canonical   <- ZIO
                                   .fromEither(CanonicalBlock.make(key, finalCarry, BinaryAttributes.empty))
                                   .mapError(msg => new IllegalArgumentException(msg))
                  _           <- blocksQ.offer(Take.single(canonical))
                yield ()
              }

            _     <- blocksQ.offer(Take.end).ignore
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
                            // spans are inclusive in Span, and block manifest offset is start position
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
            attrs   = plan.attributes // TODO: confirm size + chunk count here, consistent with the rest of the system
          yield BlobWriteResult(blob, locator, attrs))
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
