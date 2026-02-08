package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.{Manifest, ManifestEntry}
import graviton.core.ranges.Span
import graviton.core.model.Block.*
import graviton.core.types.{LocatorBucket, LocatorPath, LocatorScheme, ManifestAnnotationKey, ManifestAnnotationValue}
import graviton.core.types.BlobOffset
import graviton.core.types.Offset
import graviton.core.scan.FS.*
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
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
  metrics: MetricsRegistry = MetricsRegistry.noop,
) extends BlobStore:

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink.unwrapScoped {
      for
        startedNanos <- Clock.nanoTime
        chunker      <- graviton.streams.Chunker.current.get
        blobHasher   <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
        totalBytes   <- Ref.make(0L)

        scanDone <- Promise.make[Nothing, Long]

        tags                                                  =
          Map(
            "backend" -> "cas",
            "store"   -> "blob",
            "chunker" -> chunker.name,
          ) ++ (plan.program match
            case graviton.runtime.model.IngestProgram.Default           => Map("program" -> "default")
            case graviton.runtime.model.IngestProgram.UsePipeline(_)    => Map("program" -> "pipeline")
            case graviton.runtime.model.IngestProgram.UseScan(label, _) => Map("program" -> "scan", "scan" -> label))

        ingestPipeline: ZPipeline[Any, Throwable, Byte, Byte] =
          plan.program match
            case graviton.runtime.model.IngestProgram.Default               => ZPipeline.identity
            case graviton.runtime.model.IngestProgram.UsePipeline(pipeline) => pipeline
            case graviton.runtime.model.IngestProgram.UseScan(_, _)         => ZPipeline.identity

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

        // Run ingest program + optional scan (best-effort) + chunker + block hashing.
        _ <-
          ZIO.scoped {
            val postProgramBytes =
              ZStream
                .fromQueue(inputQ)
                .flattenTake
                .via(ingestPipeline)

            def ingest(bytes: ZStream[Any, Throwable, Byte]): ZIO[Any, Throwable, Unit] =
              bytes
                .mapChunksZIO { (chunk: Chunk[Byte]) =>
                  ZIO.attempt(blobHasher.update(chunk.toArray)) *>
                    totalBytes.update(_ + chunk.length.toLong) *>
                    ZIO.succeed(chunk)
                }
                // BlobStore APIs are `Throwable`-typed, so bridge ChunkerCore.Err at the boundary.
                .via(chunker.pipeline.mapError(graviton.streams.Chunker.toThrowable))
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
                .ensuring(blocksQ.offer(Take.end).ignore)

            plan.program match
              case graviton.runtime.model.IngestProgram.UseScan(_, build) =>
                val scan = build()
                postProgramBytes
                  .broadcast(2, maximumLag = math.max(1, streamerConfig.windowRefs))
                  .flatMap { streams =>
                    val ingestStream = streams(0)
                    val scanStream   = streams(1)

                    val scanEffect =
                      scanStream
                        .via(scan.toPipeline)
                        .runFold(0L)((n, _) => n + 1L)
                        .catchAll(_ => ZIO.succeed(0L))
                        .flatMap(n => scanDone.succeed(n).ignore)
                        .ensuring(scanDone.succeed(0L).ignore)

                    ingest(ingestStream).zipParRight(scanEffect).unit
                  }

              case _ =>
                scanDone.succeed(0L) *> ingest(postProgramBytes)
          }.forkScoped
      yield ZSink
        .foldLeftChunksZIO[Any, Throwable, Byte, Unit](()) { (_, in) =>
          inputQ.offer(Take.chunk(in)).unit
        }
        .mapZIO { _ =>
          for
            _ <- inputQ.offer(Take.end).ignore

            batch <- batchDone.await

            size <- totalBytes.get
            _    <-
              ZIO
                .fail(new IllegalArgumentException("Empty blobs are not supported (size must be > 0)"))
                .when(size <= 0L)

            digest <- ZIO.fromEither(blobHasher.digest).mapError(msg => new IllegalArgumentException(msg))
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
                              .fromEither(
                                for
                                  s    <- BlobOffset.either(start)
                                  t    <- BlobOffset.either(end)
                                  span <- Span.make(s, t)
                                yield span
                              )
                              .mapError(msg => new IllegalArgumentException(msg))
                              .map(span => ManifestEntry(e.key, span, Map.empty[ManifestAnnotationKey, ManifestAnnotationValue]))
                          }
                          .map(_.toList)
            manifest <- ZIO.fromEither(Manifest.fromEntries(entries)).mapError(msg => new IllegalArgumentException(msg))

            _ <- manifests.put(blob, manifest)

            locator <- plan.locatorHint match
                         case Some(value) => ZIO.succeed(value)
                         case None        =>
                           // SAFETY: compile-time constants matching their respective constraints
                           val scheme = LocatorScheme.applyUnsafe("cas")
                           val bucket = LocatorBucket.applyUnsafe("manifest")
                           // SAFETY: hex digest is always non-empty, no whitespace
                           val path   = LocatorPath.applyUnsafe(blob.bits.digest.hex.value)
                           ZIO.succeed(graviton.core.locator.BlobLocator(scheme, bucket, path))

            scanOutputs    <- scanDone.await
            finishedNanos  <- Clock.nanoTime
            durationSeconds = (finishedNanos - startedNanos).toDouble / 1e9
            blockCount      = batch.manifest.entries.length.toDouble

            _ <- metrics.gauge(MetricKeys.BytesIngested, size.toDouble, tags)
            _ <- metrics.gauge(MetricKeys.BlocksIngested, blockCount, tags)
            _ <- metrics.gauge(MetricKeys.ScanOutputs, scanOutputs.toDouble, tags)
            _ <- metrics.gauge(MetricKeys.UploadDuration, durationSeconds, tags)

            attrs = plan.attributes
          yield BlobWriteResult(blob, locator, attrs)
        }
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

  val layerWithMetrics: ZLayer[BlockStore & BlobManifestRepo & MetricsRegistry, Nothing, BlobStore] =
    ZLayer.fromFunction((bs: BlockStore, repo: BlobManifestRepo, reg: MetricsRegistry) => new CasBlobStore(bs, repo, metrics = reg))
