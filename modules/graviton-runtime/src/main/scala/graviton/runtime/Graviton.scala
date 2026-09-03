package graviton.runtime

import graviton.core.attributes.BlobWriteResult
import graviton.core.bytes.HashAlgo
import graviton.core.keys.BinaryKey
import graviton.core.model.InMemoryBytes
import graviton.core.scan.*
import graviton.runtime.config.BlockPersistenceConfig
import graviton.runtime.config.TransferMemoryConfig
import graviton.runtime.metrics.MetricsRegistry
import graviton.runtime.model.BlobWritePlan
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
                      .attempt(hasher.update(chunk))
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
      manifestRepo <- InMemoryBlobManifestRepo.make
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

  /** Transducer pipelines for composition. */
  object pipelines:
    @deprecated("Use basicIngestSummary for an explicit schema-backed summary", "0.8.0")
    @scala.annotation.nowarn("cat=deprecation")
    def basicIngest(blockSize: Int, algo: HashAlgo = HashAlgo.runtimeDefault) =
      IngestPipeline.countHashRechunk(blockSize, algo)

    @deprecated("Use casIngestSummary for an explicit schema-backed summary", "0.8.0")
    @scala.annotation.nowarn("cat=deprecation")
    def casIngest(blockSize: Int, algo: HashAlgo = HashAlgo.runtimeDefault) =
      CasIngest.pipeline(blockSize, algo)

    def basicIngestSummary(blockSize: Int, algo: HashAlgo = HashAlgo.runtimeDefault) =
      IngestPipeline.countHashRechunkSummary(blockSize, algo)

    def casIngestSummary(blockSize: Int, algo: HashAlgo = HashAlgo.runtimeDefault) =
      CasIngest.pipelineSummary(blockSize, algo)

    def bombGuard(maxBytes: Long) =
      BombGuard(maxBytes)

    def throughputMonitor =
      ThroughputMonitor()

    def blockVerifier(keys: IndexedSeq[BinaryKey.Block], algo: HashAlgo = HashAlgo.runtimeDefault) =
      BlockVerify.verifier(keys, algo)

    def blobVerifier(blockSize: Int, keys: IndexedSeq[BinaryKey.Block], algo: HashAlgo = HashAlgo.runtimeDefault) =
      BlockVerify.blobVerifier(blockSize, keys, algo)
