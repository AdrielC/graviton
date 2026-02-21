package graviton.runtime

import graviton.core.attributes.BlobWriteResult
import graviton.core.bytes.HashAlgo
import graviton.core.keys.BinaryKey
import graviton.core.scan.*
import graviton.runtime.metrics.MetricsRegistry
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.*
import graviton.streams.Chunker
import zio.*
import zio.stream.*

import java.nio.file.Path

/**
 * Graviton — the single entry point for content-addressed storage operations.
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
 *   - `BlockStore` — physical block persistence (FS, S3, in-memory)
 *   - `BlobManifestRepo` — manifest persistence (Postgres, in-memory)
 *   - `BlobStore` — logical blob API (CasBlobStore orchestrates the above)
 *   - `Chunker` — configurable block sizing (fixed, FastCDC, delimiter)
 *   - `MetricsRegistry` — observable counters and gauges
 *
 * Each concern is independent and swappable. The `Graviton` facade
 * wires them together for the common case.
 */
final class Graviton private (
  val blobStore: BlobStore,
  val blockStore: BlockStore,
  val manifests: BlobManifestRepo,
  val chunker: Chunker,
):

  /** Ingest a file from the local filesystem. */
  def ingestFile(
    path: Path,
    plan: BlobWritePlan = BlobWritePlan(),
  ): ZIO[Any, Throwable, BlobWriteResult] =
    Chunker.locally(chunker) {
      StoreOps.insertFile(blobStore)(path, plan)
    }

  /** Ingest raw bytes from memory. */
  def ingestBytes(
    data: Chunk[Byte],
    plan: BlobWritePlan = BlobWritePlan(),
  ): ZIO[Any, Throwable, BlobWriteResult] =
    Chunker.locally(chunker) {
      StoreOps.insertBytes(blobStore)(data, plan)
    }

  /** Ingest a byte stream. */
  def ingestStream(
    stream: ZStream[Any, Throwable, Byte],
    plan: BlobWritePlan = BlobWritePlan(),
  ): ZIO[Any, Throwable, BlobWriteResult] =
    Chunker.locally(chunker) {
      stream.run(blobStore.put(plan))
    }

  /** Retrieve all bytes for a stored blob. */
  def retrieve(key: BinaryKey): ZIO[Any, Throwable, Chunk[Byte]] =
    blobStore.get(key).runCollect

  /** Stream bytes for a stored blob (memory-efficient for large blobs). */
  def stream(key: BinaryKey): ZStream[Any, Throwable, Byte] =
    blobStore.get(key)

  /** Check if a blob exists and get its metadata. */
  def stat(key: BinaryKey): ZIO[Any, Throwable, Option[model.BlobStat]] =
    blobStore.stat(key)

  /** Delete a blob's manifest (blocks remain for dedup). */
  def delete(key: BinaryKey): ZIO[Any, Throwable, Unit] =
    blobStore.delete(key)

  /** Verify a blob by reading it back and comparing the digest. */
  def verify(key: BinaryKey): ZIO[Any, Throwable, Boolean] =
    key match
      case blob: BinaryKey.Blob =>
        for
          bytes  <- blobStore.get(blob).runCollect
          hasher <- ZIO.fromEither(graviton.core.bytes.Hasher.systemDefault).mapError(msg => new IllegalStateException(msg))
          _       = hasher.update(bytes.toArray)
          digest <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
        yield digest.hex.value == blob.bits.digest.hex.value
      case _                    =>
        ZIO.succeed(false)

object Graviton:

  /**
   * Create a filesystem-backed Graviton instance.
   *
   * Blocks are stored under `root/cas/blocks/<algo>/<hex>-<size>`.
   * Manifests are kept in memory (for production, use Postgres via `pg()`).
   */
  def fs(
    root: Path,
    chunkSize: Int = 1024 * 1024,
    metrics: MetricsRegistry = MetricsRegistry.noop,
  ): ZIO[Any, Nothing, Graviton] =
    for
      manifestRepo <- zio.Ref.make(Map.empty[BinaryKey.Blob, graviton.core.manifest.Manifest]).map { ref =>
                        new BlobManifestRepo:
                          override def put(blob: BinaryKey.Blob, manifest: graviton.core.manifest.Manifest) =
                            ref.update(_.updated(blob, manifest)).unit
                          override def get(blob: BinaryKey.Blob)                                            =
                            ref.get.map(_.get(blob))
                          override def streamBlockRefs(blob: BinaryKey.Blob)                                =
                            ZStream.fromZIO(ref.get.map(_.get(blob))).flatMap {
                              case None    => ZStream.fail(new NoSuchElementException(s"Missing manifest"))
                              case Some(m) =>
                                ZStream.fromIterable(
                                  m.entries.zipWithIndex.collect {
                                    case (graviton.core.manifest.ManifestEntry(b: BinaryKey.Block, _, _), idx) =>
                                      streaming.BlobStreamer.BlockRef(idx.toLong, b)
                                  }
                                )
                            }
                          override def delete(blob: BinaryKey.Blob)                                         =
                            ref.modify(m => (m.contains(blob), m - blob))
                      }
      blockStore    = new FsBlockStore(root)
      blobStore     = new CasBlobStore(blockStore, manifestRepo, metrics = metrics)
      chunker       = Chunker.fixed(graviton.core.types.UploadChunkSize.applyUnsafe(chunkSize))
    yield new Graviton(blobStore, blockStore, manifestRepo, chunker)

  /**
   * Create an in-memory Graviton instance (useful for tests).
   */
  def inMemory(
    chunkSize: Int = 1024 * 1024,
    metrics: MetricsRegistry = MetricsRegistry.noop,
  ): ZIO[Any, Nothing, Graviton] =
    for
      blockStore   <- InMemoryBlockStore.make
      manifestRepo <- zio.Ref.make(Map.empty[BinaryKey.Blob, graviton.core.manifest.Manifest]).map { ref =>
                        new BlobManifestRepo:
                          override def put(blob: BinaryKey.Blob, manifest: graviton.core.manifest.Manifest) =
                            ref.update(_.updated(blob, manifest)).unit
                          override def get(blob: BinaryKey.Blob)                                            =
                            ref.get.map(_.get(blob))
                          override def streamBlockRefs(blob: BinaryKey.Blob)                                =
                            ZStream.fromZIO(ref.get.map(_.get(blob))).flatMap {
                              case None    => ZStream.fail(new NoSuchElementException(s"Missing manifest"))
                              case Some(m) =>
                                ZStream.fromIterable(
                                  m.entries.zipWithIndex.collect {
                                    case (graviton.core.manifest.ManifestEntry(b: BinaryKey.Block, _, _), idx) =>
                                      streaming.BlobStreamer.BlockRef(idx.toLong, b)
                                  }
                                )
                            }
                          override def delete(blob: BinaryKey.Blob)                                         =
                            ref.modify(m => (m.contains(blob), m - blob))
                      }
      blobStore     = new CasBlobStore(blockStore, manifestRepo, metrics = metrics)
      chunker       = Chunker.fixed(graviton.core.types.UploadChunkSize.applyUnsafe(chunkSize))
    yield new Graviton(blobStore, blockStore, manifestRepo, chunker)

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
