package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.{ChecksumVerifier, HashAlgo, Hasher}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.model.Block as GBlock
import graviton.core.scan.FS.toPipeline
import graviton.core.types.*
import graviton.runtime.config.BlockPersistenceConfig
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.model.{
  BlobBlockDescription,
  BlobDescription,
  BlobInspectionPage,
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
  persistenceConfig: BlockPersistenceConfig = BlockPersistenceConfig.sequential,
  transferBudget: TransferBudget = TransferBudget.unbounded,
  hasherProvider: Hasher.Provider = Hasher.Provider.default(),
) extends BlobStore:

  private val orderedDownloadPrefetch = TransferComponent("ordered-download-prefetch")
  private val casLocatorScheme        = LocatorScheme("cas")
  private val manifestLocatorBucket   = LocatorBucket("manifest")

  private val downloadPrefetchFootprint =
    TransferFootprint.single(
      orderedDownloadPrefetch,
      streamerConfig.maximumPrefetchedBytes,
    )

  private def reserveDownload(operation: StoreOperation): ZIO[Scope, StoreError, Unit] =
    ZIO
      .fromEither(downloadPrefetchFootprint)
      .mapError(error => StoreError.InvalidInput(operation, error.getMessage))
      .flatMap(transferBudget.reserveScoped(operation, _))

  /** Binary-compatible constructor retained for clients compiled against 0.4.0. */
  def this(
    blockStore: BlockStore,
    manifests: BlobManifestRepo,
    streamerConfig: BlobStreamer.Config,
    metrics: MetricsRegistry,
  ) = this(
    blockStore,
    manifests,
    streamerConfig,
    metrics,
    CasBlobStore.IngestConfig(),
    BlockPersistenceConfig.sequential,
    TransferBudget.unbounded,
    Hasher.Provider.default(),
  )

  /** Source-compatible constructor for the bounded-ingest configuration added after 0.4.0. */
  def this(
    blockStore: BlockStore,
    manifests: BlobManifestRepo,
    streamerConfig: BlobStreamer.Config,
    metrics: MetricsRegistry,
    ingestConfig: CasBlobStore.IngestConfig,
  ) = this(
    blockStore,
    manifests,
    streamerConfig,
    metrics,
    ingestConfig,
    BlockPersistenceConfig.sequential,
    TransferBudget.unbounded,
    Hasher.Provider.default(),
  )

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
      (for
        startedNanos <- Clock.nanoTime
        chunker      <- graviton.streams.Chunker.current.get
        _            <- ZIO
                          .fail(
                            new IllegalArgumentException(
                              s"Chunker '${chunker.name}' maximumBlockBytes must be within 1..${GBlock.maxBytes}, got ${chunker.maximumBlockBytes}"
                            )
                          )
                          .unless(chunker.maximumBlockBytes >= 1 && chunker.maximumBlockBytes <= GBlock.maxBytes)
        footprint    <- ZIO
                          .fromEither(
                            ingestConfig.maximumPipelineFootprint(
                              chunker,
                              persistenceConfig,
                              blockStore,
                              plan.program,
                              streamerConfig.windowRefs.value,
                            )
                          )
                          .mapError(error => new IllegalArgumentException(error.getMessage))
        _            <- transferBudget.reserveScoped(footprint)
        _            <-
          ZIO
            .fromEither(plan.attributes.validate)
            .mapError(msg => new IllegalArgumentException(s"Invalid binary attributes in BlobWritePlan: $msg"))
        checksums    <- ZIO
                          .fromEither(ChecksumVerifier.make(plan.attributes.advertisedDigests))
                          .mapError(error => new IllegalArgumentException(error.message))
        blobHasher   <- hasherProvider
                          .make(HashAlgo.runtimeDefault)
                          .mapError(error => new IllegalStateException(error.message))

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
            .mapAccumZIO(CasBlobStore.PersistCursor.empty) { (cursor, block) =>
              val entry = BlockManifestEntry(cursor.index, cursor.offset, block.key, block.size)
              ZIO
                .fromEither(cursor.advance(block.size))
                .mapError(message => StoreError.CorruptData(StoreOperation.PutBlob, message))
                .map(next => next -> CasBlobStore.PreparedBlock(block, entry))
            }
            .mapZIOPar(
              persistenceConfig.parallelism.value,
              bufferSize = persistenceConfig.parallelism.value,
            ) { prepared =>
              blockStore
                .putBlock(prepared.block)
                .map(stored => CasBlobStore.PersistedBlock(prepared.entry, prepared.block.size, stored.status))
            }
            .runFoldZIO(CasBlobStore.PersistAcc.empty) { (acc, persisted) =>
              ZIO
                .fromEither(acc.record(persisted.size, persisted.status))
                .mapError(message => StoreError.CorruptData(StoreOperation.PutBlob, message))
                .flatMap(next => spool.append(persisted.entry).as(next))
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
                    nextSize <- ZIO.attempt(java.lang.Math.addExact(blobHasher.inputSize, chunk.length.toLong))
                    _        <- ZIO
                                  .fromEither(FileSize.either(nextSize))
                                  .mapError(message => new IllegalArgumentException(s"Blob size limit exceeded: $message"))
                    _        <- ZIO.attempt(blobHasher.update(chunk))
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
                  .broadcast(2, maximumLag = streamerConfig.windowRefs.value)
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
          ZIO
            .fromEither(checksums.update(in))
            .mapError(error => new IllegalStateException(error.message)) *>
            CasBlobStore.offerBoundedInput(inputQ, in, ingestConfig.ioChunkBytes.value, failure)
        }
        .mapZIO { _ =>
          for
            _ <- CasBlobStore.offerOrFail(inputQ, Take.end, failure)

            // The transfer checksum first becomes knowable at EOF. Validate it
            // before waiting for queued CAS writes so a mismatch interrupts the
            // scoped ingest immediately and can never publish a manifest.
            verifiedChecksums <- ZIO
                                   .fromEither(checksums.verify)
                                   .mapError(error => new IllegalArgumentException(error.message))

            persisted <- persistDone.await
            staged    <- spool.finish()
            _         <-
              ZIO
                .fail(StoreError.ManifestSpoolMismatch(persisted.blockCount, persisted.totalBytes, staged.blockCount, staged.totalSize))
                .unless(
                  persisted.blockCount == staged.blockCount && persisted.totalBytes == staged.totalSize
                )

            hashed     <- ZIO.fromEither(blobHasher.hashed).mapError(error => new IllegalArgumentException(error.message))
            _          <- ZIO
                            .fail(new IllegalArgumentException("Empty blobs are not supported (size must be > 0)"))
                            .when(hashed.size.value <= 0L)
            fileSize   <- ZIO
                            .fromEither(FileSize.either(hashed.size.value))
                            .mapError(msg => new IllegalArgumentException(msg))
            bits        = KeyBits.fromHashed(hashed)
            blob       <- ZIO.fromEither(BinaryKey.blob(bits)).mapError(msg => new IllegalArgumentException(msg))
            algoName   <- ZIO
                            .fromEither(Algo.either(blobHasher.algo.primaryName))
                            .mapError(message => new IllegalStateException(s"Invalid runtime hash algorithm: $message"))
            ingestedAt <- Clock.instant

            chunkerId <- ZIO
                           .fromEither(ManifestChunkerId.either(chunker.name))
                           .mapError(message => StoreError.InvalidInput(StoreOperation.PutManifest, s"invalid chunker identity: $message"))
            identity   = ManifestIdentity(blob, fileSize, persisted.blockCount, chunkerId)
            metadata  <- ZIO
                           .fromEither(BlobMetadataV1.fromAttributes(plan.attributes, chunkerId))
                           .mapError(message => StoreError.InvalidInput(StoreOperation.PutManifest, s"invalid blob metadata: $message"))
            _         <- manifests.putVersionedStream(identity, metadata, spool.entries, ingestedAt)

            locator <- plan.locatorHint match
                         case Some(value) => ZIO.succeed(value)
                         case None        =>
                           ZIO
                             .fromEither(LocatorPath.either(blob.bits.digest.hex.value))
                             .mapError(message =>
                               StoreError.CorruptData(
                                 StoreOperation.PutBlob,
                                 s"CAS digest could not form a locator path: $message",
                               )
                             )
                             .map(path => graviton.core.locator.BlobLocator(casLocatorScheme, manifestLocatorBucket, path))

            scanOutputs    <- scanDone.await
            finishedNanos  <- Clock.nanoTime
            durationSeconds = (finishedNanos - startedNanos).toDouble / 1e9
            blockCount      = persisted.blockCount
            freshBlocks     = persisted.freshBlocks
            dupBlocks       = persisted.duplicateBlocks

            _ <- ZIO.collectAllParDiscard(
                   Chunk(
                     metrics.gauge(MetricKeys.BytesIngested, fileSize.value.toDouble, tags),
                     metrics.gauge(MetricKeys.BlocksIngested, blockCount.toDouble, tags),
                     metrics.gauge(MetricKeys.ScanOutputs, scanOutputs.toDouble, tags),
                     metrics.histogram(MetricKeys.UploadDuration, durationSeconds, tags),
                     metrics.counter(MetricKeys.BlobIngestsTotal, tags),
                     metrics.counterBy(MetricKeys.BytesIngestedTotal, fileSize.value, tags),
                     metrics.counterBy(MetricKeys.FreshBlocksTotal, freshBlocks.toLong, tags),
                     metrics.counterBy(MetricKeys.DuplicateBlocksTotal, dupBlocks.toLong, tags),
                     metrics.counterBy(MetricKeys.FreshBlockBytesTotal, persisted.freshBlockBytes, tags),
                     metrics.counterBy(MetricKeys.DuplicateBlockBytesTotal, persisted.duplicateBlockBytes, tags),
                   )
                 )

            // Build confirmed attributes from the ingest summary (Phase B.3).
            confirmedAttrs  = {
              verifiedChecksums
                .foldLeft(plan.attributes.confirmSize(fileSize)) { case (attributes, (checksumAlgo, checksum)) =>
                  attributes.confirmDigest(checksumAlgo, checksum)
                }
                .confirmDigest(algoName, hashed.hash.bytes.hex)
            }
            validatedAttrs <- ZIO
                                .fromEither(confirmedAttrs.validate)
                                .mapError(msg => new IllegalStateException(s"Generated invalid confirmed attributes: $msg"))

            ingestStats = graviton.core.attributes.IngestStats(
                            totalBytes = fileSize.value,
                            blockCount = blockCount,
                            freshBlocks = freshBlocks,
                            duplicateBlocks = dupBlocks,
                            durationSeconds = durationSeconds,
                          )
          yield BlobWriteResult(blob, locator, validatedAttrs, ingestStats)
        }
        .mapError(StoreError.fromThrowable(StoreOperation.PutBlob))).mapError(StoreError.fromThrowable(StoreOperation.PutBlob))
    }

  override def get(key: BinaryKey.Blob): ZStream[Any, StoreError, Byte] =
    ZStream.unwrapScoped(
      reserveDownload(StoreOperation.GetBlob).as(
        BlobStreamer.streamBlob(manifests.streamBlockRefs(key), blockStore, streamerConfig)
      )
    )

  override def getRange(
    key: BinaryKey.Blob,
    start: BlobOffset,
    length: FileSize,
  ): ZStream[Any, StoreError, Byte] =
    ZStream.unwrapScoped(
      reserveDownload(StoreOperation.GetRange).as(
        BlobStreamer.streamRange(
          manifests.streamBlockRefsRange(key, start, length),
          blockStore,
          start,
          length,
          streamerConfig,
        )
      )
    )

  override def stat(key: BinaryKey.Blob): IO[StoreError, Option[BlobStat]] =
    manifests.getSummary(key).map {
      case None          => None
      case Some(summary) => Some(BlobStat(summary.totalSize, key.bits.digest, summary.ingestedAt))
    }

  override def inventoryPage(
    after: Option[graviton.runtime.model.InventoryCursor],
    limit: graviton.runtime.model.InventoryPageSize,
  ): IO[StoreError, graviton.runtime.model.InventoryPage[BlobListing]] =
    manifests.inventoryPage(after, limit).map(page => page.copy(items = page.items.map { case (key, summary) => listing(key, summary) }))

  override def inspect(key: BinaryKey.Blob): IO[StoreError, Option[BlobDescription]] =
    manifests.get(key).flatMap {
      case None         => ZIO.none
      case Some(stored) =>
        ZIO
          .attempt(description(key, stored))
          .mapError(StoreError.fromThrowable(StoreOperation.InspectBlob))
          .map(Some(_))
    }

  override def streamBlockDescriptions(key: BinaryKey.Blob): ZStream[Any, StoreError, BlobBlockDescription] =
    manifests.streamBlockRefs(key).mapAccumZIO(0L) { (offset, ref) =>
      ZIO
        .attempt {
          val size = ref.key.bits.size
          java.lang.Math.addExact(offset, size) -> BlobBlockDescription(ref.idx, ref.key, offset, size)
        }
        .mapError(StoreError.fromThrowable(StoreOperation.InspectBlob))
    }

  override def inspectPage(
    key: BinaryKey.Blob,
    after: Option[graviton.runtime.model.InventoryCursor],
    limit: graviton.runtime.model.InventoryPageSize,
  ): IO[StoreError, Option[BlobInspectionPage]] =
    manifests.getSummary(key).flatMap {
      case None          => ZIO.none
      case Some(summary) =>
        for
          offset <- ZIO
                      .fromEither(BlobStore.decodeManifestCursor(key, after))
                      .mapError(StoreError.InvalidInput(StoreOperation.InspectBlob, _))
          rows   <- streamBlockDescriptions(key).drop(offset).take(limit.value.toLong + 1L).runCollect
          page    = rows.take(limit.value)
          next   <- ZIO.foreach(Option.when(rows.length > limit.value)(offset.toLong + page.length.toLong))(index =>
                      ZIO
                        .fromEither(BlobStore.encodeManifestCursor(key, index))
                        .mapError(StoreError.InvalidInput(StoreOperation.InspectBlob, _))
                    )
          listing = BlobListing(
                      key,
                      BlobStat(summary.totalSize, key.bits.digest, summary.ingestedAt),
                      summary.blockCount,
                    )
        yield Some(BlobInspectionPage(listing, page, next))
    }

  override def metadata(key: BinaryKey.Blob): IO[StoreError, Option[BlobMetadataV1]] =
    manifests.getMetadata(key)

  override def delete(key: BinaryKey.Blob): IO[StoreError, Unit] =
    manifests.delete(key).unit

  override def healthCheck: IO[StoreError, Unit] =
    blockStore.healthCheck
      .mapError(StoreError.fromThrowable(StoreOperation.HealthCheck)) *> manifests.healthCheck

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

    /**
     * Conservative live-byte ceiling for Graviton's queues and in-flight block
     * writes. This excludes one caller-owned input chunk, the chunker's
     * documented working set, and backend-local buffers.
     */
    def maximumPipelineBytes(
      chunker: graviton.streams.Chunker,
      persistence: BlockPersistenceConfig,
    ): Long =
      maximumQueuedBytes(chunker) +
        (2L * persistence.parallelism.value.toLong + 1L) * chunker.maximumBlockBytes.toLong

    /**
     * Every Graviton-owned live allocation, including backend-declared replay,
     * replica, or erasure buffers. The composed total is reserved exactly once.
     */
    def maximumPipelineFootprint(
      chunker: graviton.streams.Chunker,
      persistence: BlockPersistenceConfig,
      blockStore: BlockStore,
    ): Either[TransferFootprint.Error, TransferFootprint] =
      maximumPipelineFootprint(
        chunker,
        persistence,
        blockStore,
        graviton.runtime.model.IngestProgram.Default,
        scanWindowRefs = 1,
      )

    def maximumPipelineFootprint(
      chunker: graviton.streams.Chunker,
      persistence: BlockPersistenceConfig,
      blockStore: BlockStore,
      program: graviton.runtime.model.IngestProgram,
      scanWindowRefs: Int,
    ): Either[TransferFootprint.Error, TransferFootprint] =
      val maximumBlock = chunker.maximumBlockBytes.toLong
      for
        inputQueue <- TransferFootprint.single(
                        TransferComponent("ingest-input-queue"),
                        inputBufferChunks.toLong * ioChunkBytes.value.toLong,
                      )
        blockQueue <- TransferFootprint.single(
                        TransferComponent("canonical-block-queue"),
                        blockBufferBlocks.toLong * maximumBlock,
                      )
        chunkerSet <- TransferFootprint.single(TransferComponent("chunker-working-set"), maximumBlock)
        inFlight   <- TransferFootprint.single(
                        TransferComponent("persistence-in-flight-blocks"),
                        persistence.parallelism.value.toLong * maximumBlock,
                      )
        backendOne <- BlockTransferFootprint.writeOf(blockStore, chunker.maximumBlockBytes)
        backend    <- backendOne.scaled(
                        persistence.parallelism.value,
                        TransferComponent("parallel-backend-write-buffers"),
                      )
        scanQueues <- program match
                        case graviton.runtime.model.IngestProgram.UseScan(_, _) =>
                          TransferFootprint
                            .multiply(ioChunkBytes.value.toLong, 2L * math.max(1, scanWindowRefs).toLong)
                            .flatMap(TransferFootprint.single(TransferComponent("scan-broadcast-queues"), _))
                        case _                                                  => Right(TransferFootprint.empty)
        total      <- TransferFootprint.combine(Chunk(inputQueue, blockQueue, chunkerSet, inFlight, backend, scanQueues))
      yield total

  private final case class PersistCursor(index: BlockIndex, offset: Offset):
    def advance(size: BlockSize): Either[String, PersistCursor] =
      for
        nextIndex  <- index.next.toRight("Manifest block index overflow")
        blockBytes <- Offset.fromBlockSize(size)
        nextOffset <- offset.checkedAdd(blockBytes)
      yield PersistCursor(nextIndex, nextOffset)

  private object PersistCursor:
    val empty: PersistCursor = PersistCursor(BlockIndex.Min, Offset.Min)

  private final case class PreparedBlock(
    block: CanonicalBlock,
    entry: BlockManifestEntry,
  )

  private final case class PersistedBlock(
    entry: BlockManifestEntry,
    size: BlockSize,
    status: BlockStoredStatus,
  )

  private final case class PersistAcc(
    blockCount: BlockCount,
    totalBytes: ContentLength,
    freshBlocks: BlockCount,
    duplicateBlocks: BlockCount,
    freshBlockBytes: ContentLength,
    duplicateBlockBytes: ContentLength,
  ):
    def record(size: BlockSize, status: BlockStoredStatus): Either[String, PersistAcc] =
      for
        blockBytes          <- ContentLength.fromBlockSize(size)
        nextBlockCount      <- blockCount.next.toRight("Manifest block count exceeded its supported maximum")
        nextTotalBytes      <- totalBytes.checkedAdd(blockBytes)
        nextFreshBlocks     <-
          if status == BlockStoredStatus.Fresh then freshBlocks.next.toRight("Fresh block count overflow")
          else Right(freshBlocks)
        nextDuplicateBlocks <-
          if status == BlockStoredStatus.Duplicate then duplicateBlocks.next.toRight("Duplicate block count overflow")
          else Right(duplicateBlocks)
        nextFreshBytes      <-
          if status == BlockStoredStatus.Fresh then freshBlockBytes.checkedAdd(blockBytes)
          else Right(freshBlockBytes)
        nextDuplicateBytes  <-
          if status == BlockStoredStatus.Duplicate then duplicateBlockBytes.checkedAdd(blockBytes)
          else Right(duplicateBlockBytes)
      yield PersistAcc(
        nextBlockCount,
        nextTotalBytes,
        nextFreshBlocks,
        nextDuplicateBlocks,
        nextFreshBytes,
        nextDuplicateBytes,
      )

    def summary: PersistSummary =
      PersistSummary(blockCount, totalBytes, freshBlocks, duplicateBlocks, freshBlockBytes, duplicateBlockBytes)

  private object PersistAcc:
    val empty: PersistAcc =
      PersistAcc(BlockCount.Min, ContentLength.Min, BlockCount.Min, BlockCount.Min, ContentLength.Min, ContentLength.Min)

  private final case class PersistSummary(
    blockCount: BlockCount,
    totalBytes: ContentLength,
    freshBlocks: BlockCount,
    duplicateBlocks: BlockCount,
    freshBlockBytes: ContentLength,
    duplicateBlockBytes: ContentLength,
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
    (ZLayer.service[BlockStore] ++ ZLayer.service[BlobManifestRepo] ++ TransferBudget.default) >>>
      ZLayer.fromFunction((bs: BlockStore, repo: BlobManifestRepo, budget: TransferBudget) =>
        new CasBlobStore(bs, repo, transferBudget = budget): BlobStore
      )

  val layerWithMetrics: ZLayer[BlockStore & BlobManifestRepo & MetricsRegistry, Nothing, BlobStore] =
    (ZLayer.service[BlockStore] ++ ZLayer.service[BlobManifestRepo] ++ ZLayer.service[MetricsRegistry] ++ TransferBudget.default) >>>
      ZLayer.fromFunction((bs: BlockStore, repo: BlobManifestRepo, reg: MetricsRegistry, budget: TransferBudget) =>
        new CasBlobStore(bs, repo, metrics = reg, transferBudget = budget): BlobStore
      )

  /**
   * Production composition that coordinates complete blob operations with
   * repository maintenance. The permit remains held while an upload sink
   * consumes input or a download stream emits output.
   */
  val coordinatedLayer: ZLayer[BlockStore & BlobManifestRepo & MaintenanceCoordinator, Nothing, BlobStore] =
    (ZLayer.service[BlockStore] ++ ZLayer.service[BlobManifestRepo] ++ ZLayer.service[MaintenanceCoordinator] ++ TransferBudget.default) >>>
      ZLayer.fromFunction((bs: BlockStore, repo: BlobManifestRepo, coordinator: MaintenanceCoordinator, budget: TransferBudget) =>
        new CoordinatedBlobStore(new CasBlobStore(bs, repo, transferBudget = budget), coordinator): BlobStore
      )

  /** Coordinated production composition with an explicit metrics registry. */
  val coordinatedLayerWithMetrics: ZLayer[BlockStore & BlobManifestRepo & MaintenanceCoordinator & MetricsRegistry, Nothing, BlobStore] =
    (ZLayer.service[BlockStore] ++ ZLayer.service[BlobManifestRepo] ++ ZLayer.service[MaintenanceCoordinator] ++
      ZLayer.service[MetricsRegistry] ++ TransferBudget.default) >>> ZLayer.fromFunction(
      (
        bs: BlockStore,
        repo: BlobManifestRepo,
        coordinator: MaintenanceCoordinator,
        reg: MetricsRegistry,
        budget: TransferBudget,
      ) => new CoordinatedBlobStore(new CasBlobStore(bs, repo, metrics = reg, transferBudget = budget), coordinator): BlobStore
    )

  /** Coordinated composition with explicit bounded block-write concurrency. */
  val coordinatedLayerWithMetricsAndPersistence: ZLayer[
    BlockStore & BlobManifestRepo & MaintenanceCoordinator & MetricsRegistry & BlockPersistenceConfig & TransferBudget,
    Nothing,
    BlobStore,
  ] =
    ZLayer.fromFunction(
      (
        bs: BlockStore,
        repo: BlobManifestRepo,
        coordinator: MaintenanceCoordinator,
        reg: MetricsRegistry,
        persistence: BlockPersistenceConfig,
        budget: TransferBudget,
      ) =>
        new CoordinatedBlobStore(
          new CasBlobStore(bs, repo, metrics = reg, persistenceConfig = persistence, transferBudget = budget),
          coordinator,
        ): BlobStore
    )
