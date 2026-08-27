package graviton.cli

import graviton.core.bytes.*
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.types.*
import graviton.runtime.config.{GarbageCollectionConfig, GravitonConfig, MaintenanceConfig}
import graviton.runtime.stores.*
import graviton.streams.Chunker
import zio.*
import zio.stream.*

import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, Paths, StandardCopyOption}

/**
 * Graviton CLI: command-line interface for CAS blob operations.
 *
 * Commands:
 *   ingest <file>       Ingest a file into the CAS store
 *   stat <blobKey>      Show metadata for a stored blob
 *   get <blobKey> <out> Retrieve a blob to a local file
 *   verify <blobKey>    Verify blob integrity (read + re-hash)
 *   delete <blobKey>    Delete a blob manifest (deduplicated blocks remain)
 *   list                List persisted blob manifests
 *   gc                  Dry-run orphan block collection (`--apply` quarantines)
 *
 * Uses filesystem-backed block store by default.
 * Configure via GRAVITON_DATA_DIR and GRAVITON_CHUNK_SIZE env vars.
 */
object GravitonCli extends ZIOAppDefault:

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      cfg  <- ZIO.config(GravitonConfig.config)
      args <- ZIOAppArgs.getArgs
      _    <- args.toList match
                case "ingest" :: filePath :: _       => ingest(Paths.get(filePath), cfg)
                case "stat" :: blobKeyHex :: Nil     => stat(blobKeyHex, cfg)
                case "get" :: blobKeyHex :: out :: _ => retrieve(blobKeyHex, Paths.get(out), cfg)
                case "verify" :: blobKeyHex :: _     => verify(blobKeyHex, cfg)
                case "delete" :: blobKeyHex :: _     => delete(blobKeyHex, cfg)
                case "list" :: Nil                   => list(cfg)
                case "gc" :: rest                    =>
                  ZIO.config(GarbageCollectionConfig.config).flatMap(gc(rest, cfg, _))
                case "help" :: _                     => printUsage
                case other                           =>
                  Console.printLineError(s"Unknown command: ${other.mkString(" ")}") *> printUsage *> ZIO.fail(ExitCode.failure)
    yield ()

  private def ingest(filePath: Path, cfg: GravitonConfig): ZIO[Any, Any, Unit] =
    val absPath = filePath.toAbsolutePath
    for
      _      <- Console.printLine(s"Ingesting: $absPath")
      _      <- ZIO.unless(Files.exists(absPath))(
                  Console.printLineError(s"File not found: $absPath") *> ZIO.fail(ExitCode.failure)
                )
      store  <- makeStore(cfg)
      result <- Chunker.locally(Chunker.fixed(UploadChunkSize.applyUnsafe(cfg.chunkSize))) {
                  StoreOps.insertFile(store)(absPath)
                }
      blobKey = result.key
      stats   = result.stats
      _      <- Console.printLine(s"  Blob ID:      ${blobKey.bits.render}")
      _      <- Console.printLine(s"  Locator:      ${result.locator.render}")
      _      <- Console.printLine(s"  Total bytes:  ${stats.totalBytes}")
      _      <- Console.printLine(s"  Blocks:       ${stats.blockCount} (${stats.freshBlocks} fresh, ${stats.duplicateBlocks} duplicate)")
      _      <- Console.printLine(s"  Dedup ratio:  ${f"${stats.dedupRatio * 100}%.1f"}%")
      _      <- Console.printLine(s"  Duration:     ${f"${stats.durationSeconds}%.3f"}s")
      _      <- Console.printLine("  Done.")
    yield ()

  private def stat(blobKeyHex: String, cfg: GravitonConfig): ZIO[Any, Any, Unit] =
    for
      store   <- makeStore(cfg)
      blobKey <- parseBlobKey(blobKeyHex)
      statOpt <- store.stat(blobKey)
      _       <- statOpt match
                   case Some(s) =>
                     Console.printLine(s"  Blob ID:       ${blobKey.bits.render}") *>
                       Console.printLine(s"  Size:          ${s.size.value} bytes") *>
                       Console.printLine(s"  Digest:        ${s.digest.hex.value}") *>
                       Console.printLine(s"  Last modified: ${s.lastModified}")
                   case None    =>
                     Console.printLineError(s"Blob not found: $blobKeyHex") *>
                       ZIO.fail(ExitCode.failure)
    yield ()

  private def retrieve(blobKeyHex: String, outPath: Path, cfg: GravitonConfig): ZIO[Any, Any, Unit] =
    for
      store   <- makeStore(cfg)
      blobKey <- parseBlobKey(blobKeyHex)
      statOpt <- store.stat(blobKey)
      stat    <- ZIO
                   .fromOption(statOpt)
                   .mapError(_ => new NoSuchElementException(s"Blob not found: $blobKeyHex"))
      target   = outPath.toAbsolutePath.normalize()
      _       <- Console.printLine(s"Retrieving ${blobKey.bits.render} to $target")
      written <- writeAtomically(store.get(blobKey), target)
      _       <- ZIO
                   .fail(new IllegalStateException(s"Expected ${stat.size.value} bytes but wrote $written"))
                   .unless(written == stat.size.value)
      _       <- Console.printLine(s"  Written $written bytes to $target")
    yield ()

  private def verify(blobKeyHex: String, cfg: GravitonConfig): ZIO[Any, Any, Unit] =
    for
      store   <- makeStore(cfg)
      blobKey <- parseBlobKey(blobKeyHex)
      statOpt <- store.stat(blobKey)
      _       <- ZIO
                   .fromOption(statOpt)
                   .mapError(_ => new NoSuchElementException(s"Blob not found: $blobKeyHex"))
      _       <- Console.printLine(s"Verifying ${blobKey.bits.render}...")
      hasher  <- ZIO.fromEither(Hasher.hasher(blobKey.bits.algo)).mapError(msg => new IllegalStateException(msg))
      bytes   <- store
                   .get(blobKey)
                   .mapChunksZIO(chunk => ZIO.attempt(hasher.update(chunk.toArray)).as(chunk))
                   .runCount
      digest  <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
      ok       = digest.hex.value == blobKey.bits.digest.hex.value && bytes == blobKey.bits.size
      _       <- if ok then Console.printLine(s"  PASS: $bytes bytes, digest matches")
                 else
                   Console.printLineError(
                     s"  FAIL: expected ${blobKey.bits.digest.hex.value}/${blobKey.bits.size} bytes, got ${digest.hex.value}/$bytes bytes"
                   ) *> ZIO.fail(ExitCode.failure)
    yield ()

  private def delete(blobKeyText: String, cfg: GravitonConfig): ZIO[Any, Any, Unit] =
    for
      store   <- makeStore(cfg)
      blobKey <- parseBlobKey(blobKeyText)
      stat    <- store.stat(blobKey)
      _       <- stat match
                   case None    =>
                     Console.printLineError(s"Blob not found: $blobKeyText") *>
                       ZIO.fail(ExitCode.failure)
                   case Some(_) =>
                     store.delete(blobKey) *>
                       Console.printLine(s"Deleted manifest for ${blobKey.bits.render}; shared blocks were retained.")
    yield ()

  private def list(cfg: GravitonConfig): ZIO[Any, Any, Unit] =
    for
      store <- makeStore(cfg)
      blobs <- store.list
      _     <- ZIO.foreachDiscard(blobs) { item =>
                 Console.printLine(s"${item.key.bits.render}\t${item.stat.size.value}\t${item.stat.lastModified}\t${item.blockCount}")
               }
    yield ()

  private def gc(
    args: List[String],
    cfg: GravitonConfig,
    gcConfig: GarbageCollectionConfig,
  ): ZIO[Any, Any, Unit] =
    val applyChanges = args.contains("--apply")
    val ageHours     = args.sliding(2).collectFirst { case List("--min-age-hours", raw) => raw.toLongOption }.flatten.getOrElse(24L)
    for
      _                 <- ZIO.fail(new IllegalArgumentException("--min-age-hours must be non-negative")).when(ageHours < 0L)
      root              <- ZIO.attempt(Paths.get(cfg.dataDir).toAbsolutePath)
      maintenanceConfig <- ZIO.config(MaintenanceConfig.config)
      coordinator       <- FileMaintenanceCoordinator.make(root, maintenanceConfig)
      blockStore         = new FsBlockStore(root)
      repo               = new FsBlobManifestRepo(root)
      report            <- GarbageCollection
                             .sweep(ageHours.hours, dryRun = !applyChanges) { block =>
                               Console.printLine(s"  ${block.key.bits.render}\t${block.token}")
                             }
                             .provide(
                               (ZLayer.succeed[BlobManifestRepo](repo) ++
                                 ZLayer.succeed[BlockMaintenance](blockStore) ++
                                 ZLayer.succeed[MaintenanceCoordinator](coordinator) ++
                                 ZLayer.succeed[GarbageCollectionConfig](gcConfig)) >>>
                                 GarbageCollection.live
                             )
      _                 <- Console.printLine(
                             s"GC ${if report.dryRun then "DRY-RUN" else "QUARANTINED"}: " +
                               s"scanned=${report.scannedBlocks} references=${report.referencedBlockRefs} " +
                               s"candidates=${report.candidateBlocks} bytes=${report.candidateBytes} " +
                               s"quarantined=${report.quarantinedBlocks}"
                           )
    yield ()

  private def makeStore(cfg: GravitonConfig): ZIO[Any, Any, BlobStore] =
    for
      root              <- ZIO.attempt(Paths.get(cfg.dataDir).toAbsolutePath)
      _                 <- ZIO.attemptBlocking(Files.createDirectories(root))
      maintenanceConfig <- ZIO.config(MaintenanceConfig.config)
      coordinator       <- FileMaintenanceCoordinator.make(root, maintenanceConfig)
      blockStore         = new FsBlockStore(root)
      repo               = new FsBlobManifestRepo(root)
      rawStore           = new CasBlobStore(blockStore, repo)
      blobStore          = new CoordinatedBlobStore(rawStore, coordinator)
    yield blobStore

  private def parseBlobKey(value: String): ZIO[Any, Any, BinaryKey.Blob] =
    for
      bits <- ZIO.fromEither(KeyBits.fromString(value)).mapError(msg => new IllegalArgumentException(msg))
      key  <- ZIO.fromEither(BinaryKey.blob(bits)).mapError(msg => new IllegalArgumentException(msg))
    yield key

  private def writeAtomically(stream: ZStream[Any, Throwable, Byte], target: Path): Task[Long] =
    for
      _       <- ZIO.attemptBlocking(Files.createDirectories(target.getParent))
      written <- ZIO.acquireReleaseWith(
                   ZIO.attemptBlocking(Files.createTempFile(target.getParent, s".${target.getFileName}-", ".tmp"))
                 )(tmp => ZIO.attemptBlocking(Files.deleteIfExists(tmp)).ignore) { tmp =>
                   stream.run(ZSink.fromFile(tmp.toFile)).flatMap { count =>
                     ZIO.attemptBlocking {
                       try Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                       catch
                         case _: AtomicMoveNotSupportedException =>
                           Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                       count
                     }
                   }
                 }
    yield written

  private val printUsage: ZIO[Any, Any, Unit] =
    Console.printLine(
      """Graviton CLI: Content-Addressed Storage Engine
        |
        |Usage:
        |  graviton ingest <file>              Ingest a file into the CAS store
        |  graviton stat <blobId>              Show metadata for a stored blob
        |  graviton get <blobId> <output>      Retrieve a blob to a local file
        |  graviton verify <blobId>            Verify blob integrity
        |  graviton delete <blobId>            Delete its manifest; retain shared blocks
        |  graviton list                       List durable blob manifests
        |  graviton gc [--min-age-hours N]     Preview orphan blocks (default age: 24h)
        |  graviton gc --apply [...]           Move orphan blocks into quarantine
        |  graviton help                       Show this help
        |
        |Blob IDs use: <algorithm>:<hex-digest>:<byte-length>
        |
        |Environment (via ZIO Config, GRAVITON_ prefix):
        |  GRAVITON_DATA_DIR      Data directory (default: .graviton)
        |  GRAVITON_CHUNK_SIZE    Block size in bytes (default: 1048576)
        |  GRAVITON_GC_MAX_REFERENCES_PER_PARTITION  GC heap bound per exact-mark partition (default: 8192)
        |  GRAVITON_GC_WORKSPACE_DIRECTORY           Optional parent directory for temporary GC spools
        |  GRAVITON_MAINTENANCE_NAMESPACE            Shared repository lock namespace (default: graviton)
        |  GRAVITON_MAINTENANCE_ACQUISITION_TIMEOUT  Lock acquisition timeout (default: 30s)
        |  GRAVITON_MAINTENANCE_POLL_INTERVAL        Lock retry interval (default: 100ms)
        |""".stripMargin
    )
