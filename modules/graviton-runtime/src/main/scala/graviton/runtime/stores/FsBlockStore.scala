package graviton.runtime.stores

import graviton.core.bytes.HashAlgo
import graviton.core.bytes.Digest
import graviton.core.keys.BinaryKey
import graviton.core.keys.KeyBits
import graviton.runtime.model.*
import zio.*
import zio.stream.*

import java.nio.ByteBuffer
import java.nio.channels.{Channels, FileChannel}
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.util.UUID

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
) extends BlockStore
    with BlockMaintenance:

  override def putBlock(
    block: CanonicalBlock,
    plan: BlockWritePlan = BlockWritePlan(),
  ): IO[Throwable, StoredBlock] =
    storeBlock(block).map(status => StoredBlock(block.key, block.size, status))

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
    ZStream
      .acquireReleaseWith(
        ZIO.attemptBlocking {
          val channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
          Channels.newInputStream(channel)
        }
      )(is => ZIO.attemptBlocking(is.close()).orDie)
      .flatMap(is => ZStream.fromInputStream(is, chunkSize = 64 * 1024))

  override def exists(key: BinaryKey.Block): ZIO[Any, Throwable, Boolean] =
    val path = pathFor(key)
    ZIO.attemptBlocking {
      if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then false
      else if Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then true
      else throw new IllegalStateException(s"Block path is not a regular file: $path")
    }

  override def healthCheck: ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      val directory = root.resolve(prefix)
      Files.createDirectories(directory)
      val probe     = Files.createTempFile(directory, ".ready-", ".tmp")
      try
        val channel = FileChannel.open(probe, StandardOpenOption.WRITE)
        try channel.force(true)
        finally channel.close()
      finally
        val _ = Files.deleteIfExists(probe)
      ()
    }

  override def inventory: ZStream[Any, Throwable, BlockInventoryEntry] =
    val base = root.resolve(prefix)
    FsBlobManifestRepo
      .walkFiles(base) { path =>
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !path.getFileName.toString.endsWith(".tmp")
      }
      .mapZIO { path =>
        ZIO.attemptBlocking {
          val key = keyFromPath(path).fold(message => throw new IllegalArgumentException(message), identity)
          BlockInventoryEntry(key, Files.size(path), Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant)
        }
      }

  override def quarantine(entry: BlockInventoryEntry): Task[QuarantinedBlock] =
    Clock.instant.flatMap { now =>
      ZIO.attemptBlocking {
        val source      = pathFor(entry.key)
        val token       = UUID.randomUUID().toString
        val destination = root.resolve("cas/quarantine").resolve(token).resolve(root.resolve(prefix).relativize(source))
        Files.createDirectories(destination.getParent)
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        fsyncDirectory(source.getParent)
        fsyncDirectory(destination.getParent)
        QuarantinedBlock(entry.key, token, entry.size, now)
      }
    }

  override def restore(block: QuarantinedBlock): Task[Unit] =
    ZIO.attemptBlocking {
      val destination = pathFor(block.key)
      val source      = quarantinedPath(block)
      Files.createDirectories(destination.getParent)
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
      fsyncDirectory(source.getParent)
      fsyncDirectory(destination.getParent)
    }

  override def purge(block: QuarantinedBlock): Task[Unit] =
    ZIO.attemptBlocking {
      val path = quarantinedPath(block)
      if !Files.deleteIfExists(path) then throw new NoSuchElementException(s"Quarantined block is missing: ${block.token}")
      fsyncDirectory(path.getParent)
    }

  private def storeBlock(block: CanonicalBlock): IO[Throwable, BlockStoredStatus] =
    val dest = pathFor(block.key)
    ZIO.attemptBlocking {
      Files.createDirectories(dest.getParent)
      if Files.exists(dest, LinkOption.NOFOLLOW_LINKS) then verifyExisting(dest, block)
      else
        val tmp = Files.createTempFile(dest.getParent, "blk-", ".tmp")
        try
          val channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
          try
            val bytes = ByteBuffer.wrap(block.bytes.toArray)
            while bytes.hasRemaining do
              val _ = channel.write(bytes)
            channel.force(true)
          finally channel.close()

          try
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE)
            fsyncDirectory(dest.getParent)
            BlockStoredStatus.Fresh
          catch case _: java.nio.file.FileAlreadyExistsException => verifyExisting(dest, block)
        finally
          try { val _ = Files.deleteIfExists(tmp); () }
          catch case _: java.io.IOException => ()
    }

  /** Compare a bounded in-memory block with an existing file without rewriting it. */
  private def verifyExisting(dest: Path, block: CanonicalBlock): BlockStoredStatus =
    if !Files.isRegularFile(dest, LinkOption.NOFOLLOW_LINKS) then
      throw new IllegalStateException(s"Existing block path is not a regular file: $dest")
    else if Files.size(dest) != block.size.value.toLong then
      throw new IllegalStateException(s"Existing block does not match its content key: $dest")
    else
      val channel = FileChannel.open(dest, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
      try
        val buffer = ByteBuffer.allocate(math.min(64 * 1024, block.size.value))
        var offset = 0
        var done   = false
        while !done do
          buffer.clear()
          val read = channel.read(buffer)
          if read < 0 then done = true
          else
            buffer.flip()
            while buffer.hasRemaining do
              if buffer.get() != block.bytes(offset) then
                throw new IllegalStateException(s"Existing block does not match its content key: $dest")
              offset += 1
        if offset != block.size.value then throw new IllegalStateException(s"Existing block does not match its content key: $dest")
        BlockStoredStatus.Duplicate
      finally channel.close()

  private[stores] def pathFor(key: BinaryKey.Block): Path =
    val algo = algoPathSegment(key.bits.algo)
    val hex  = key.bits.digest.hex.value
    val name = s"$hex-${key.bits.size}"

    val base = root.resolve(prefix)
    base.resolve(algo).resolve(name)

  private def quarantinedPath(block: QuarantinedBlock): Path =
    root.resolve("cas/quarantine").resolve(block.token).resolve(root.resolve(prefix).relativize(pathFor(block.key)))

  private def keyFromPath(path: Path): Either[String, BinaryKey.Block] =
    val algorithm = Option(path.getParent).flatMap(parent => Option(parent.getFileName)).map(_.toString).getOrElse("")
    val fileName  = path.getFileName.toString
    val separator = fileName.lastIndexOf('-')
    for
      _      <- Either.cond(separator > 0, (), s"Invalid block filename: $path")
      digest <- Digest.fromString(fileName.substring(0, separator))
      size   <- fileName.substring(separator + 1).toLongOption.toRight(s"Invalid block size: $path")
      algo   <- HashAlgo.values.find(a => algoPathSegment(a) == algorithm).toRight(s"Invalid block algorithm directory: $algorithm")
      bits   <- KeyBits.create(algo, digest, size)
      key    <- BinaryKey.block(bits)
    yield key

  private def fsyncDirectory(directory: Path): Unit =
    val channel = FileChannel.open(directory, StandardOpenOption.READ)
    try channel.force(true)
    finally channel.close()

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
