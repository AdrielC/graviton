package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.model.Block as GBlock
import graviton.core.scan.FS.toPipeline
import graviton.core.types.*
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.model.{
  BlobBlockDescription,
  BlobDescription,
  BlobListing,
  BlobStat,
  BlobWritePlan,
  BlobWriteResult,
  BlockManifestEntry,
  BlockStoredStatus,
  CanonicalBlock,
}
import graviton.runtime.streaming.BlobStreamer
import zio.*
import zio.stream.*

/**
 * Streaming-first CAS blob store:
 * - chunk bytes into bounded blocks (never empty)
 * - store blocks by CAS key (via [[BlockStore]])
 * - build and persist manifest (via [[BlobManifestRepo]])
 * - serve reads by streaming refs from DB and bytes from the block store
 *
 * The per-block keying stage uses [[CasIngest.blockKeyDeriver]], a composable
 * `Transducer` that derives content-addressed keys for each block.
 */
final class CasBlobStore(
  blockStore: BlockStore,
  manifests: BlobManifestRepo,
  streamerConfig: BlobStreamer.Config = BlobStreamer.Config(),
  metrics: MetricsRegistry = MetricsRegistry.noop,
  ingestConfig: CasBlobStore.IngestConfig = CasBlobStore.IngestConfig(),
) extends BlobStore:

  /** Binary-compatible constructor retained for clients compiled against 0.4.0. */
  def this(
    blockStore: BlockStore,
    manifests: BlobManifestRepo,
    streamerConfig: BlobStreamer.Config,
    metrics: MetricsRegistry,
  ) = this(blockStore, manifests, streamerConfig, metrics, CasBlobStore.IngestConfig())

  /**
   * Pipeline that converts post-chunker Blocks into CanonicalBlocks.
   *
   * Delegates to [[graviton.core.scan.CasIngest.blockKeyDeriver]] for per-block
   * hashing and `BinaryKey.Block` derivation, then wraps each `KeyedBlock` as a
   * `CanonicalBlock` for persistence.
   */
  private val blockKeyPipeline: ZPipeline[Any, Throwable, GBlock, CanonicalBlock] =
    import graviton.core.scan.CasIngest
    val toBytes: ZPipeline[Any, Nothing, GBlock, Chunk[Byte]]                        =
      ZPipeline.map(block => block: Chunk[Byte])
    val keyDeriver: ZPipeline[Any, Nothing, Chunk[Byte], CasIngest.KeyedBlock]       =
      CasIngest.blockKeyDeriver().toPipeline
    val toCanonical: ZPipeline[Any, Throwable, CasIngest.KeyedBlock, CanonicalBlock] =
      ZPipeline.mapZIO { kb =>
        ZIO
          .fromEither(CanonicalBlock.make(kb.key, kb.payload, BinaryAttributes.empty))
          .mapError(msg => new IllegalArgumentException(msg))
      }
    toBytes >>> keyDeriver >>> toCanonical

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink.unwrapScoped {
      for
        startedNanos <- Clock.nanoTime
        chunker      <- graviton.streams.Chunker.current.get
        _            <- ZIO
                          .fail(
                            new IllegalArgumentException(
                              s"Chunker '${chunker.name}' maximumBlockBytes must be within 1..${GBlock.maxBytes}, got ${chunker.maximumBlockBytes}"
                            )
                          )
                          .unless(chunker.maximumBlockBytes >= 1 && chunker.maximumBlockBytes <= GBlock.maxBytes)
        _            <-
          ZIO
            .fromEither(plan.attributes.validate)
            .mapError(msg => new IllegalArgumentException(s"Invalid binary attributes in BlobWritePlan: $msg"))
        blobHasher   <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
        totalBytes   <- Ref.make(0L)

        scanDone <- Promise.make[Nothing, Long]
        spool    <- ManifestSpool.scoped
        failure  <- Promise.make[Nothing, Throwable]

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

        // Stage 1: copied, fixed-size I/O chunks. Queue capacity is a byte-level
        // contract, not a promise about arbitrary upstream Chunk sizes.
        inputQ <- Queue.bounded[Take[Throwable, Byte]](ingestConfig.inputBufferChunks)

        // Stage 2: compile-time bounded canonical blocks.
        blocksQ <- Queue.bounded[Take[Throwable, CanonicalBlock]](ingestConfig.blockBufferBlocks)

        // Stage 3: scalar persistence summary. Manifest entries are spooled to
        // disk and replayed into the durable repository after the blob digest is known.
        persistDone <- Promise.make[Throwable, CasBlobStore.PersistSummary]

        // Persist blocks as they're produced.
        _ <-
          (ZStream
            .fromQueue(blocksQ)
            .flattenTake
            .runFoldZIO(CasBlobStore.PersistAcc.empty) { (acc, block) =>
              for
                stored <- blockStore.putBlock(block)
                entry  <- ZIO
                            .fromEither(BlockManifestEntry.make(acc.index, acc.offset, block.key, block.size.value))
                            .mapError(message => new IllegalArgumentException(message))
                _      <- spool.append(entry)
              yield acc.record(block.size.value, stored.status)
            }
            .flatMap(acc => ZIO.succeed(acc.summary))
            .sandbox
            .mapError(_.squash)
            .tapError(error => failure.succeed(error).ignore *> blocksQ.takeAll.unit *> inputQ.takeAll.unit)
            .intoPromise(persistDone))
            .forkScoped

        // Run ingest program + optional scan + chunker + per-block keying.
        _ <-
          ZIO.scoped {
            val postProgramBytes =
              ZStream
                .fromQueue(inputQ)
                .flattenTake
                .via(ingestPipeline)
                .rechunk(ingestConfig.ioChunkBytes.value)

            def ingest(bytes: ZStream[Any, Throwable, Byte]): ZIO[Any, Throwable, Unit] =
              bytes
                .mapChunksZIO { (chunk: Chunk[Byte]) =>
                  for
                    size <- totalBytes.modify { current =>
                              val next = java.lang.Math.addExact(current, chunk.length.toLong)
                              next -> next
                            }
                    _    <- ZIO
                              .fromEither(FileSize.either(size))
                              .mapError(message => new IllegalArgumentException(s"Blob size limit exceeded: $message"))
                    _    <- ZIO.attempt(blobHasher.update(chunk))
                  yield chunk
                }
                // BlobStore APIs are `Throwable`-typed, so bridge ChunkerCore.Err at the boundary.
                .via(chunker.pipeline.mapError(graviton.streams.Chunker.toThrowable))
                .mapZIO { block =>
                  ZIO
                    .fail(
                      new IllegalStateException(
                        s"Chunker '${chunker.name}' emitted ${block.length} bytes above its declared ${chunker.maximumBlockBytes}-byte maximum"
                      )
                    )
                    .when(block.length > chunker.maximumBlockBytes)
                    .as(block)
                }
                // Per-block keying: hash each block → derive BinaryKey.Block → CanonicalBlock.
                .via(blockKeyPipeline)
                .runForeach(canon => CasBlobStore.offerOrFail(blocksQ, Take.single(canon), failure))
                .tapError(err => ZIO.logWarning(s"Ingest stream failed: ${err.getMessage}"))
                .catchAll { err =>
                  failure.poll.flatMap {
                    case Some(_) => inputQ.takeAll.unit
                    case None    =>
                      CasBlobStore.offerUntilAccepted(blocksQ, Take.fail(err)) *>
                        failure.succeed(err).ignore *>
                        inputQ.takeAll.unit
                  }
                }
                .ensuring(CasBlobStore.offerOrFail(blocksQ, Take.end, failure).ignore)

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
                        .tapError(err => ZIO.logWarning(s"Scan pipeline failed: ${err.getMessage}"))
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
          CasBlobStore.offerBoundedInput(inputQ, in, ingestConfig.ioChunkBytes.value, failure)
        }
        .mapZIO { _ =>
          for
            _ <- CasBlobStore.offerOrFail(inputQ, Take.end, failure)

            persisted <- persistDone.await
            staged    <- spool.finish()
            _         <-
              ZIO
                .fail(
                  new IllegalStateException(
                    s"Manifest spool mismatch: persisted ${persisted.blockCount}/${persisted.totalBytes}, staged ${staged.blockCount}/${staged.totalSize}"
                  )
                )
                .unless(
                  persisted.blockCount == staged.blockCount && persisted.totalBytes == staged.totalSize
                )

            size <- totalBytes.get
            _    <-
              ZIO
                .fail(new IllegalArgumentException("Empty blobs are not supported (size must be > 0)"))
                .when(size <= 0L)

            digest     <- ZIO.fromEither(blobHasher.digest).mapError(msg => new IllegalArgumentException(msg))
            bits       <- ZIO
                            .fromEither(KeyBits.create(blobHasher.algo, digest, size))
                            .mapError(msg => new IllegalArgumentException(msg))
            blob       <- ZIO.fromEither(BinaryKey.blob(bits)).mapError(msg => new IllegalArgumentException(msg))
            fileSize   <- ZIO.fromEither(FileSize.either(size)).mapError(msg => new IllegalArgumentException(msg))
            ingestedAt <- Clock.instant

            _ <- manifests.putStream(blob, fileSize, persisted.blockCount, spool.entries, ingestedAt)

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
            blockCount      = persisted.blockCount
            freshBlocks     = persisted.freshBlocks
            dupBlocks       = persisted.duplicateBlocks

            _ <- metrics.gauge(MetricKeys.BytesIngested, size.toDouble, tags)
            _ <- metrics.gauge(MetricKeys.BlocksIngested, blockCount.toDouble, tags)
            _ <- metrics.gauge(MetricKeys.ScanOutputs, scanOutputs.toDouble, tags)
            _ <- metrics.histogram(MetricKeys.UploadDuration, durationSeconds, tags)
            _ <- metrics.counter(MetricKeys.BlobIngestsTotal, tags)
            _ <- metrics.counterBy(MetricKeys.BytesIngestedTotal, size, tags)
            _ <- metrics.counterBy(MetricKeys.FreshBlocksTotal, freshBlocks.toLong, tags)
            _ <- metrics.counterBy(MetricKeys.DuplicateBlocksTotal, dupBlocks.toLong, tags)

            // Build confirmed attributes from the ingest summary (Phase B.3).
            confirmedAttrs  = {
              val algoName  = Algo.applyUnsafe(blobHasher.algo.primaryName)
              val hexDigest = HexLower.applyUnsafe(digest.hex.value)
              plan.attributes
                .confirmSize(fileSize)
                .confirmDigest(algoName, hexDigest)
            }
            validatedAttrs <- ZIO
                                .fromEither(confirmedAttrs.validate)
                                .mapError(msg => new IllegalStateException(s"Generated invalid confirmed attributes: $msg"))

            ingestStats = graviton.core.attributes.IngestStats(
                            totalBytes = size,
                            blockCount = blockCount,
                            freshBlocks = freshBlocks,
                            duplicateBlocks = dupBlocks,
                            durationSeconds = durationSeconds,
                          )
          yield BlobWriteResult(blob, locator, validatedAttrs, ingestStats)
        }
    }

  override def get(key: BinaryKey.Blob): ZStream[Any, Throwable, Byte] =
    BlobStreamer.streamBlob(manifests.streamBlockRefs(key), blockStore, streamerConfig)

  override def stat(key: BinaryKey.Blob): ZIO[Any, Throwable, Option[BlobStat]] =
    manifests.getSummary(key).map {
      case None          => None
      case Some(summary) => Some(BlobStat(summary.totalSize, key.bits.digest, summary.ingestedAt))
    }

  override def list: ZIO[Any, Throwable, Chunk[BlobListing]] =
    manifests.listSummaries.map(_.map { case (key, summary) => listing(key, summary) })

  override def inspect(key: BinaryKey.Blob): ZIO[Any, Throwable, Option[BlobDescription]] =
    manifests.get(key).map(_.map(stored => description(key, stored)))

  override def delete(key: BinaryKey.Blob): ZIO[Any, Throwable, Unit] =
    manifests.delete(key).unit

  override def healthCheck: ZIO[Any, Throwable, Unit] =
    blockStore.healthCheck *> manifests.healthCheck

  private def listing(blob: BinaryKey.Blob, stored: StoredManifest): BlobListing =
    val totalSize = stored.manifest.entries.foldLeft(0L) { (acc, entry) =>
      acc + (entry.span.endInclusive.value - entry.span.startInclusive.value + 1L)
    }
    BlobListing(
      key = blob,
      stat = BlobStat(FileSize.unsafe(totalSize), blob.bits.digest, stored.ingestedAt),
      blockCount = stored.manifest.entries.length,
    )

  private def listing(blob: BinaryKey.Blob, summary: StoredManifestSummary): BlobListing =
    BlobListing(
      key = blob,
      stat = BlobStat(summary.totalSize, blob.bits.digest, summary.ingestedAt),
      blockCount = summary.blockCount,
    )

  private def description(blob: BinaryKey.Blob, stored: StoredManifest): BlobDescription =
    val blocks = Chunk.fromIterable(
      stored.manifest.entries.zipWithIndex.map { case (entry, index) =>
        entry.key match
          case block: BinaryKey.Block =>
            val offset = entry.span.startInclusive.value
            val size   = entry.span.endInclusive.value - offset + 1L
            BlobBlockDescription(index.toLong, block, offset, size)
          case other                  =>
            throw new IllegalStateException(s"Blob manifest contains non-block key: $other")
      }
    )
    BlobDescription(listing(blob, stored), blocks)

object CasBlobStore:
  /** Byte-level queue limits for upload ingest. */
  final case class IngestConfig(
    ioChunkBytes: UploadChunkSize = UploadChunkSize(64 * 1024),
    inputBufferChunks: Int = 4,
    blockBufferBlocks: Int = 2,
  ):
    require(inputBufferChunks > 0, "inputBufferChunks must be positive")
    require(blockBufferBlocks > 0, "blockBufferBlocks must be positive")

    /**
     * Bytes retained by the two queues, excluding one caller-owned upstream
     * chunk, the chunker's documented working set, and backend-local buffers.
     */
    def maximumQueuedBytes(chunker: graviton.streams.Chunker): Long =
      inputBufferChunks.toLong * ioChunkBytes.value.toLong +
        blockBufferBlocks.toLong * chunker.maximumBlockBytes.toLong

  private final case class PersistAcc(
    index: Long,
    offset: Long,
    freshBlocks: Int,
    duplicateBlocks: Int,
  ):
    def record(size: Int, status: BlockStoredStatus): PersistAcc =
      copy(
        index = index + 1L,
        offset = java.lang.Math.addExact(offset, size.toLong),
        freshBlocks = freshBlocks + (if status == BlockStoredStatus.Fresh then 1 else 0),
        duplicateBlocks = duplicateBlocks + (if status == BlockStoredStatus.Duplicate then 1 else 0),
      )

    def summary: PersistSummary =
      PersistSummary(index.toInt, offset, freshBlocks, duplicateBlocks)

  private object PersistAcc:
    val empty: PersistAcc = PersistAcc(0L, 0L, 0, 0)

  private final case class PersistSummary(
    blockCount: Int,
    totalBytes: Long,
    freshBlocks: Int,
    duplicateBlocks: Int,
  )

  private def offerOrFail[A](
    queue: Queue[Take[Throwable, A]],
    take: Take[Throwable, A],
    failure: Promise[Nothing, Throwable],
  ): IO[Throwable, Unit] =
    def failIfSignalled: IO[Throwable, Unit] =
      failure.poll.flatMap {
        case Some(error) => error.flatMap(ZIO.fail(_))
        case None        => ZIO.unit
      }

    failIfSignalled *>
      queue.offer(take).flatMap {
        case true  => failIfSignalled
        case false => failIfSignalled
      }

  private def offerUntilAccepted[A](
    queue: Queue[Take[Throwable, A]],
    take: Take[Throwable, A],
  ): UIO[Unit] =
    queue.offer(take).unit

  private def offerBoundedInput(
    queue: Queue[Take[Throwable, Byte]],
    input: Chunk[Byte],
    chunkBytes: Int,
    failure: Promise[Nothing, Throwable],
  ): IO[Throwable, Unit] =
    def loop(offset: Int): IO[Throwable, Unit] =
      if offset >= input.length then ZIO.unit
      else
        val end  = math.min(input.length, offset + chunkBytes)
        val copy = Chunk.fromArray(input.slice(offset, end).toArray)
        offerOrFail(queue, Take.chunk(copy), failure) *> ZIO.suspendSucceed(loop(end))

    loop(0)

  val layer: ZLayer[BlockStore & BlobManifestRepo, Nothing, BlobStore] =
    ZLayer.fromFunction((bs: BlockStore, repo: BlobManifestRepo) => new CasBlobStore(bs, repo))

  val layerWithMetrics: ZLayer[BlockStore & BlobManifestRepo & MetricsRegistry, Nothing, BlobStore] =
    ZLayer.fromFunction((bs: BlockStore, repo: BlobManifestRepo, reg: MetricsRegistry) => new CasBlobStore(bs, repo, metrics = reg))

  /**
   * Production composition that coordinates complete blob operations with
   * repository maintenance. The permit remains held while an upload sink
   * consumes input or a download stream emits output.
   */
  val coordinatedLayer: ZLayer[BlockStore & BlobManifestRepo & MaintenanceCoordinator, Nothing, BlobStore] =
    ZLayer.fromFunction((bs: BlockStore, repo: BlobManifestRepo, coordinator: MaintenanceCoordinator) =>
      new CoordinatedBlobStore(new CasBlobStore(bs, repo), coordinator): BlobStore
    )

  /** Coordinated production composition with an explicit metrics registry. */
  val coordinatedLayerWithMetrics: ZLayer[BlockStore & BlobManifestRepo & MaintenanceCoordinator & MetricsRegistry, Nothing, BlobStore] =
    ZLayer.fromFunction(
      (
        bs: BlockStore,
        repo: BlobManifestRepo,
        coordinator: MaintenanceCoordinator,
        reg: MetricsRegistry,
      ) => new CoordinatedBlobStore(new CasBlobStore(bs, repo, metrics = reg), coordinator): BlobStore
    )
