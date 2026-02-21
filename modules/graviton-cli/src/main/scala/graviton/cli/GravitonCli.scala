package graviton.cli

import graviton.core.bytes.*
import graviton.core.keys.BinaryKey
import graviton.core.types.*
import graviton.runtime.stores.*
import graviton.streams.Chunker
import zio.*
import zio.stream.*

import java.nio.file.{Files, Path, Paths}

/**
 * Graviton CLI — command-line interface for CAS blob operations.
 *
 * Commands:
 *   ingest <file>       Ingest a file into the CAS store
 *   stat <blobKey>      Show metadata for a stored blob
 *   get <blobKey> <out> Retrieve a blob to a local file
 *   verify <blobKey>    Verify blob integrity (read + re-hash)
 *
 * Uses filesystem-backed block store by default.
 */
object GravitonCli extends ZIOAppDefault:

  private val defaultRoot: Path =
    Paths.get(sys.env.getOrElse("GRAVITON_DATA_DIR", ".graviton"))

  private val defaultChunkSize: Int =
    sys.env.get("GRAVITON_CHUNK_SIZE").flatMap(_.toIntOption).getOrElse(1024 * 1024) // 1 MiB

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      args <- ZIOAppArgs.getArgs
      _    <- args.toList match
                case "ingest" :: filePath :: _       => ingest(Paths.get(filePath))
                case "stat" :: blobKeyHex :: Nil     => stat(blobKeyHex)
                case "get" :: blobKeyHex :: out :: _ => retrieve(blobKeyHex, Paths.get(out))
                case "verify" :: blobKeyHex :: _     => verify(blobKeyHex)
                case "help" :: _                     => printUsage
                case other                           =>
                  Console.printLineError(s"Unknown command: ${other.mkString(" ")}") *> printUsage *> ZIO.fail(ExitCode.failure)
    yield ()

  private def ingest(filePath: Path): ZIO[Any, Any, Unit] =
    val absPath = filePath.toAbsolutePath
    for
      _      <- Console.printLine(s"Ingesting: $absPath")
      _      <- ZIO.unless(Files.exists(absPath))(
                  Console.printLineError(s"File not found: $absPath") *> ZIO.fail(ExitCode.failure)
                )
      store  <- makeStore
      result <- Chunker.locally(Chunker.fixed(UploadChunkSize.applyUnsafe(defaultChunkSize))) {
                  StoreOps.insertFile(store)(absPath)
                }
      blobKey = result.key match
                  case b: BinaryKey.Blob => b
                  case other             => other // shouldn't happen
      stats   = result.stats
      _      <- Console.printLine(s"  Blob key:     ${blobKey.bits.digest.hex.value}")
      _      <- Console.printLine(s"  Locator:      ${result.locator.render}")
      _      <- Console.printLine(s"  Total bytes:  ${stats.totalBytes}")
      _      <- Console.printLine(s"  Blocks:       ${stats.blockCount} (${stats.freshBlocks} fresh, ${stats.duplicateBlocks} duplicate)")
      _      <- Console.printLine(s"  Dedup ratio:  ${f"${stats.dedupRatio * 100}%.1f"}%%")
      _      <- Console.printLine(s"  Duration:     ${f"${stats.durationSeconds}%.3f"}s")
      _      <- Console.printLine("  Done.")
    yield ()

  private def stat(blobKeyHex: String): ZIO[Any, Any, Unit] =
    for
      store   <- makeStore
      blobKey <- parseBlobKey(blobKeyHex)
      statOpt <- store.stat(blobKey)
      _       <- statOpt match
                   case Some(s) =>
                     Console.printLine(s"  Size:          ${s.size.value} bytes") *>
                       Console.printLine(s"  Digest:        ${s.digest.hex.value}") *>
                       Console.printLine(s"  Last modified: ${s.lastModified}")
                   case None    =>
                     Console.printLineError(s"Blob not found: $blobKeyHex")
    yield ()

  private def retrieve(blobKeyHex: String, outPath: Path): ZIO[Any, Any, Unit] =
    for
      store   <- makeStore
      blobKey <- parseBlobKey(blobKeyHex)
      _       <- Console.printLine(s"Retrieving blob ${blobKeyHex.take(16)}... to $outPath")
      bytes   <- store.get(blobKey).runCollect
      _       <- ZIO.attemptBlocking {
                   Files.createDirectories(outPath.getParent)
                   Files.write(outPath, bytes.toArray)
                 }
      _       <- Console.printLine(s"  Written ${bytes.length} bytes to $outPath")
    yield ()

  private def verify(blobKeyHex: String): ZIO[Any, Any, Unit] =
    for
      store   <- makeStore
      blobKey <- parseBlobKey(blobKeyHex)
      _       <- Console.printLine(s"Verifying blob ${blobKeyHex.take(16)}...")
      bytes   <- store.get(blobKey).runCollect
      hasher  <- ZIO.fromEither(Hasher.systemDefault).mapError(msg => new IllegalStateException(msg))
      _        = hasher.update(bytes.toArray)
      digest  <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
      ok       = digest.hex.value == blobKey.bits.digest.hex.value
      _       <- if ok then Console.printLine(s"  PASS: ${bytes.length} bytes, digest matches")
                 else Console.printLineError(s"  FAIL: expected ${blobKey.bits.digest.hex.value}, got ${digest.hex.value}")
    yield ()

  private def makeStore: ZIO[Any, Any, BlobStore] =
    for
      root      <- ZIO.attempt(defaultRoot.toAbsolutePath)
      _         <- ZIO.attemptBlocking(Files.createDirectories(root))
      blockStore = new FsBlockStore(root)
      repo      <- InMemoryManifestRepo.make
      blobStore  = new CasBlobStore(blockStore, repo)
    yield blobStore

  private def parseBlobKey(hex: String): ZIO[Any, Any, BinaryKey.Blob] =
    for
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(msg => new IllegalStateException(msg))
      digest <- ZIO.fromEither(Digest.fromString(hex)).mapError(msg => new IllegalArgumentException(msg))
      bits   <- ZIO
                  .fromEither(graviton.core.keys.KeyBits.create(hasher.algo, digest, 0L))
                  .mapError(msg => new IllegalArgumentException(msg))
      key    <- ZIO.fromEither(BinaryKey.blob(bits)).mapError(msg => new IllegalArgumentException(msg))
    yield key

  private val printUsage: ZIO[Any, Any, Unit] =
    Console.printLine(
      """Graviton CLI — Content-Addressed Storage Engine
        |
        |Usage:
        |  graviton ingest <file>              Ingest a file into the CAS store
        |  graviton stat <blobKeyHex>          Show metadata for a stored blob
        |  graviton get <blobKeyHex> <output>  Retrieve a blob to a local file
        |  graviton verify <blobKeyHex>        Verify blob integrity
        |  graviton help                       Show this help
        |
        |Environment:
        |  GRAVITON_DATA_DIR    Data directory (default: .graviton)
        |  GRAVITON_CHUNK_SIZE  Block size in bytes (default: 1048576)
        |""".stripMargin
    )

/**
 * Minimal in-memory manifest repo for the CLI.
 *
 * In production, this would be backed by Postgres or another persistent store.
 * For the CLI, we track manifests in memory per session.
 */
private final class InMemoryManifestRepo(
  ref: Ref[Map[BinaryKey.Blob, graviton.core.manifest.Manifest]]
) extends BlobManifestRepo:

  override def put(blob: BinaryKey.Blob, manifest: graviton.core.manifest.Manifest): ZIO[Any, Throwable, Unit] =
    ref.update(_.updated(blob, manifest)).unit

  override def get(blob: BinaryKey.Blob): ZIO[Any, Throwable, Option[graviton.core.manifest.Manifest]] =
    ref.get.map(_.get(blob))

  override def streamBlockRefs(
    blob: BinaryKey.Blob
  ): ZStream[Any, Throwable, graviton.runtime.streaming.BlobStreamer.BlockRef] =
    ZStream.fromZIO(ref.get.map(_.get(blob))).flatMap {
      case None    => ZStream.fail(new NoSuchElementException(s"Missing manifest for ${blob.bits.digest.hex.value}"))
      case Some(m) =>
        ZStream.fromIterable(
          m.entries.zipWithIndex.collect { case (graviton.core.manifest.ManifestEntry(b: BinaryKey.Block, _, _), idx) =>
            graviton.runtime.streaming.BlobStreamer.BlockRef(idx.toLong, b)
          }
        )
    }

  override def delete(blob: BinaryKey.Blob): ZIO[Any, Throwable, Boolean] =
    ref.modify { map =>
      if map.contains(blob) then (true, map - blob)
      else (false, map)
    }

private object InMemoryManifestRepo:
  def make: UIO[InMemoryManifestRepo] =
    Ref.make(Map.empty[BinaryKey.Blob, graviton.core.manifest.Manifest]).map(new InMemoryManifestRepo(_))
