package graviton.runtime

import graviton.core.attributes.BlobWriteResult
import graviton.core.bytes.HashAlgo
import graviton.core.keys.BinaryKey
import graviton.core.model.InMemoryBytes
import graviton.core.scan.*
import graviton.runtime.config.BlockPersistenceConfig
import graviton.runtime.config.TransferMemoryConfig
import graviton.runtime.metrics.MetricsRegistry
import graviton.runtime.model.{BlobWritePlan, InventoryCursor, InventoryPage, InventoryPageSize}
import graviton.runtime.stores.*
import graviton.streams.{BoundedByteStream, Chunker}
import zio.*
import zio.stream.*

import java.nio.file.Path

/**
 * Graviton is the single entry point for content-addressed storage operations.
 *
 * This facade provides a clean, discoverable API over the lower-level store
 * services. It is the recommended way to interact with Graviton from
 * application code:
 *
 * {{{
 * val graviton = Graviton.fs(Paths.get("/data/graviton"))
 *
 * for
 *   result   <- graviton.ingestFile(Paths.get("photo.jpg"))
 *   _        <- Console.printLine(s"Stored as: ${result.key}")
 *   bytes    <- graviton.retrieve(result.key)
 *   _        <- Console.printLine(s"Retrieved ${bytes.length} bytes")
 *   verified <- graviton.verify(result.key)
 *   _        <- Console.printLine(s"Verified: $verified")
 * yield ()
 * }}}
 *
 * == Design ==
 *
 * Graviton is composed of orthogonal services:
 *   - `BlockStore`: physical block persistence (FS, S3, in-memory)
 *   - `BlobManifestRepo`: manifest persistence (filesystem, Postgres, in-memory)
 *   - `BlobStore`: logical blob API (CasBlobStore orchestrates the above)
 *   - `Chunker`: configurable block sizing (fixed, FastCDC, delimiter)
 *   - `MetricsRegistry`: observable counters and gauges
 *
 * Each concern is independent and swappable. The `Graviton` facade
 * wires them together for the common case.
 */
final class Graviton private (
  val blobStore: BlobStore,
  val blockStore: BlockStore,
  val manifests: BlobManifestRepo,
  val chunker: Chunker,
  val maintenance: MaintenanceCoordinator,
):

  /** Ingest a file from the local filesystem. */
  def ingestFile(
    path: Path,
    plan: BlobWritePlan = BlobWritePlan(),
  ): IO[StoreError, BlobWriteResult] =
    Chunker.locally(chunker) {
      StoreOps.insertFile(blobStore)(path, plan)
    }

  /** Ingest raw bytes from memory. */
  def ingestBytes(
    data: Chunk[Byte],
    plan: BlobWritePlan = BlobWritePlan(),
  ): IO[StoreError, BlobWriteResult] =
    Chunker.locally(chunker) {
      StoreOps.insertBytes(blobStore)(data, plan)
    }

  /** Ingest a byte stream. */
  def ingestStream[E](
    stream: ZStream[Any, E, Byte],
    plan: BlobWritePlan = BlobWritePlan(),
  ): IO[E | StoreError, BlobWriteResult] =
    Chunker.locally(chunker) {
      stream.run(blobStore.put(plan))
    }

  /**
   * Retrieve a small blob in memory, rejecting values larger than 16 MiB.
   * Arbitrary-size consumers must use [[stream]].
   */
  def retrieve(key: BinaryKey.Blob): IO[StoreError | BoundedByteStream.Error, InMemoryBytes] =
    BoundedByteStream.collectInMemory(blobStore.get(key))

  /** Stream bytes for a stored blob (memory-efficient for large blobs). */
  def stream(key: BinaryKey.Blob): ZStream[Any, StoreError, Byte] =
    blobStore.get(key)

  /** Check if a blob exists and get its metadata. */
  def stat(key: BinaryKey.Blob): IO[StoreError, Option[model.BlobStat]] =
    blobStore.stat(key)

  /** Delete a blob's manifest (blocks remain for dedup). */
  def delete(key: BinaryKey.Blob): IO[StoreError, Unit] =
    blobStore.delete(key)

  /** Verify a blob by reading it back and comparing the digest. */
  def verify(key: BinaryKey.Blob): IO[StoreError, Boolean] =
    for
      hasher <- ZIO
                  .fromEither(graviton.core.bytes.Hasher.hasher(key.bits.algo))
                  .mapError(StoreError.CorruptData(StoreOperation.GetBlob, _))
      bytes  <- blobStore
                  .get(key)
                  .mapChunksZIO(chunk =>
                    ZIO
                      .attempt(hasher.update(chunk.toArray))
                      .mapError(StoreError.fromThrowable(StoreOperation.GetBlob))
                      .as(chunk)
                  )
                  .runCount
      digest <- ZIO.fromEither(hasher.digest).mapError(StoreError.CorruptData(StoreOperation.GetBlob, _))
    yield digest.hex.value == key.bits.digest.hex.value && bytes == key.bits.size

object Graviton:

  /**
   * Create a filesystem-backed Graviton instance.
   *
   * Blocks are stored under `root/cas/blocks/<algo>/<hex>-<size>`.
   * Manifests are stored atomically under
   * `root/cas/manifests/<algo>/<hex>-<size>.manifest`, so a fresh process can
   * retrieve, inspect, verify, or delete previously ingested blobs.
   */
  def fs(
    root: Path,
    chunkSize: Int = 1024 * 1024,
    metrics: MetricsRegistry = MetricsRegistry.noop,
  ): ZIO[Any, Nothing, Graviton] =
    for
      maintenance <- FileMaintenanceCoordinator.make(root).orDie
      budget      <- TransferBudget.make(TransferMemoryConfig.Default)
      manifestRepo = new FsBlobManifestRepo(root)
      blockStore   = new FsBlockStore(root)
      rawStore     = new CasBlobStore(
                       blockStore,
                       manifestRepo,
                       metrics = metrics,
                       persistenceConfig = BlockPersistenceConfig.default,
                       transferBudget = budget,
                     )
      blobStore    = new CoordinatedBlobStore(rawStore, maintenance)
      chunker      = Chunker.fixed(graviton.core.types.UploadChunkSize.applyUnsafe(chunkSize))
    yield new Graviton(blobStore, blockStore, manifestRepo, chunker, maintenance)

  /**
   * Create an in-memory Graviton instance (useful for tests).
   */
  def inMemory(
    chunkSize: Int = 1024 * 1024,
    metrics: MetricsRegistry = MetricsRegistry.noop,
  ): ZIO[Any, Nothing, Graviton] =
    for
      blockStore   <- InMemoryBlockStore.make
      manifestRepo <- makeInlineManifestRepo
      maintenance  <- MaintenanceCoordinator.inProcess().orDie
      budget       <- TransferBudget.make(TransferMemoryConfig.Default)
      rawStore      = new CasBlobStore(
                        blockStore,
                        manifestRepo,
                        metrics = metrics,
                        persistenceConfig = BlockPersistenceConfig.default,
                        transferBudget = budget,
                      )
      blobStore     = new CoordinatedBlobStore(rawStore, maintenance)
      chunker       = Chunker.fixed(graviton.core.types.UploadChunkSize.applyUnsafe(chunkSize))
    yield new Graviton(blobStore, blockStore, manifestRepo, chunker, maintenance)

  private def makeInlineManifestRepo: UIO[BlobManifestRepo] =
    zio.Ref.make(Map.empty[BinaryKey.Blob, stores.StoredManifest]).map { ref =>
      new BlobManifestRepo:
        override def put(blob: BinaryKey.Blob, manifest: graviton.core.manifest.Manifest, ingestedAt: java.time.Instant) =
          ref.update(_.updated(blob, stores.StoredManifest(manifest, ingestedAt))).unit
        override def get(blob: BinaryKey.Blob)                                                                           =
          ref.get.map(_.get(blob))
        override def inventoryPage(after: Option[InventoryCursor], limit: InventoryPageSize)                             =
          for
            anchor <- ZIO
                        .fromEither(
                          after.fold[Either[String, Option[String]]](Right(None))(cursor =>
                            InventoryCursor.decode(cursor, graviton.runtime.model.InventoryNamespace.InMemory).map(Some(_))
                          )
                        )
                        .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
            values <- ref.get
            ordered = values.toList.sortBy(_._1.bits.render)
            page    = ordered.dropWhile { case (key, _) => anchor.exists(_ >= key.bits.render) }.take(limit.value + 1)
            items   = Chunk.fromIterable(page.take(limit.value).map { case (key, stored) =>
                        val summary = stores.StoredManifestSummary(
                          graviton.core.types.FileSize.unsafe(stored.manifest.size),
                          stored.manifest.entries.length,
                          stored.ingestedAt,
                        )
                        key -> summary
                      })
            next   <- ZIO.foreach(page.lift(limit.value - 1).filter(_ => page.length > limit.value)) { case (key, _) =>
                        ZIO
                          .fromEither(InventoryCursor.encode(graviton.runtime.model.InventoryNamespace.InMemory, key.bits.render))
                          .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
                      }
          yield InventoryPage(items, next)
        override def streamBlockRefs(blob: BinaryKey.Blob)                                                               =
          ZStream.fromZIO(ref.get.map(_.get(blob))).flatMap {
            case None         => ZStream.fail(StoreError.NotFound(StoreOperation.GetManifest, blob))
            case Some(stored) =>
              ZStream.fromIterable(
                stored.manifest.entries.zipWithIndex.collect { case (graviton.core.manifest.ManifestEntry(b: BinaryKey.Block, _, _), idx) =>
                  streaming.BlobStreamer.BlockRef(idx.toLong, b)
                }
              )
          }
        override def delete(blob: BinaryKey.Blob)                                                                        =
          ref.modify(m => (m.contains(blob), m - blob))
        override def healthCheck                                                                                         =
          ZIO.unit
    }

  /** Transducer pipelines for composition. */
  object pipelines:
    def basicIngest(blockSize: Int, algo: HashAlgo = HashAlgo.runtimeDefault) =
      IngestPipeline.countHashRechunk(blockSize, algo)

    def casIngest(blockSize: Int, algo: HashAlgo = HashAlgo.runtimeDefault) =
      CasIngest.pipeline(blockSize, algo)

    def bombGuard(maxBytes: Long) =
      BombGuard(maxBytes)

    def throughputMonitor =
      ThroughputMonitor()

    def blockVerifier(keys: IndexedSeq[BinaryKey.Block], algo: HashAlgo = HashAlgo.runtimeDefault) =
      BlockVerify.verifier(keys, algo)

    def blobVerifier(blockSize: Int, keys: IndexedSeq[BinaryKey.Block], algo: HashAlgo = HashAlgo.runtimeDefault) =
      BlockVerify.blobVerifier(blockSize, keys, algo)
