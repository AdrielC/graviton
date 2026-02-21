package graviton.runtime.stores

import graviton.core.bytes.HashAlgo
import graviton.core.keys.BinaryKey
import graviton.runtime.model.*
import zio.*
import zio.stream.*

import java.nio.file.{Files, Path, StandardCopyOption}

/**
 * Filesystem-backed block store.
 *
 * Layout:
 *   <root>/<prefix>/<algo>/<hex>-<size>
 *
 * Intended for local dev and embedded integration tests (no MinIO required).
 */
final class FsBlockStore(
  root: Path,
  prefix: String = "cas/blocks",
) extends BlockStore:

  override def putBlocks(plan: BlockWritePlan = BlockWritePlan()): BlockSink =
    ZSink
      .foldLeftZIO(FsBlockStore.Acc.empty) { (acc, block: CanonicalBlock) =>
        for
          storedStatus <- storeBlock(block)
          entry        <- ZIO
                            .fromEither(BlockManifestEntry.make(acc.index, acc.offset, block.key, block.size.value))
                            .mapError(msg => new IllegalArgumentException(msg))
          next          = acc.next(entry, block, storedStatus)
        yield next
      }
      .mapZIO(_.toResult)
      .ignoreLeftover

  override def get(key: BinaryKey.Block): ZStream[Any, Throwable, Byte] =
    val path = pathFor(key)
    ZStream.acquireReleaseWith(ZIO.attemptBlocking(Files.newInputStream(path)))(is => ZIO.attemptBlocking(is.close()).orDie).flatMap { is =>
      ZStream.fromInputStream(is, chunkSize = 64 * 1024)
    }

  override def exists(key: BinaryKey.Block): ZIO[Any, Throwable, Boolean] =
    ZIO.attemptBlocking(Files.exists(pathFor(key)))

  private def storeBlock(block: CanonicalBlock): IO[Throwable, BlockStoredStatus] =
    val dest = pathFor(block.key)
    ZIO.attemptBlocking {
      if Files.exists(dest) then BlockStoredStatus.Duplicate
      else
        Files.createDirectories(dest.getParent)
        val tmp = Files.createTempFile(dest.getParent, "blk-", ".tmp")
        try
          Files.write(tmp, block.bytes.toArray)
          try
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE)
            BlockStoredStatus.Fresh
          catch
            case _: java.nio.file.FileAlreadyExistsException =>
              BlockStoredStatus.Duplicate
        finally
          try { val _ = Files.deleteIfExists(tmp); () }
          catch case _: Throwable => ()
    }

  private def pathFor(key: BinaryKey.Block): Path =
    val algo = algoPathSegment(key.bits.algo)
    val hex  = key.bits.digest.hex.value
    val name = s"$hex-${key.bits.size}"

    val base = root.resolve(prefix)
    base.resolve(algo).resolve(name)

  private def algoPathSegment(algo: HashAlgo): String =
    algo match
      case HashAlgo.Sha256 => "sha256"
      case HashAlgo.Blake3 => "blake3"
      case other           => other.primaryName

object FsBlockStore:
  def layer(root: Path, prefix: String = "cas/blocks"): ULayer[BlockStore] =
    ZLayer.succeed(new FsBlockStore(root, prefix))

  private[stores] final case class Acc(
    entries: ChunkBuilder[BlockManifestEntry],
    stored: ChunkBuilder[StoredBlock],
    offset: Long,
    index: Long,
  ):
    def next(entry: BlockManifestEntry, block: CanonicalBlock, status: BlockStoredStatus): Acc =
      entries += entry
      stored += StoredBlock(block.key, block.size, status)
      copy(
        offset = offset + block.size.value.toLong,
        index = index + 1L,
      )

    def toResult: IO[Throwable, BlockBatchResult] =
      ZIO
        .fromEither(BlockManifest.build(entries.result()))
        .mapError(msg => new IllegalArgumentException(msg))
        .map { manifest =>
          BlockBatchResult(
            manifest = manifest,
            stored = stored.result(),
            forward = Chunk.empty,
            frames = Chunk.empty,
          )
        }

  private[stores] object Acc:
    def empty: Acc =
      Acc(
        entries = ChunkBuilder.make[BlockManifestEntry](),
        stored = ChunkBuilder.make[StoredBlock](),
        offset = 0L,
        index = 0L,
      )
