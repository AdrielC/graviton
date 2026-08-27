package graviton.runtime.stores

import graviton.core.keys.{BinaryKey, KeyBits}
import zio.*
import zio.stream.ZStream

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream, EOFException}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor, StandardOpenOption}
import java.time.Instant
import scala.collection.mutable

/**
 * Exact, disk-spilled mark index for repository-scale garbage collection.
 *
 * Each reference and eligible inventory entry is streamed once into a bounded
 * record file. Matching then recursively partitions both files by digest
 * nibbles. A leaf holds at most `maxReferencesPerPartition` keys in heap. A
 * deliberately adversarial bucket that cannot be split any further falls back
 * to a slow exact scan rather than allocating an unbounded set or treating a
 * live block as garbage.
 */
private[stores] object GarbageCollectionSpool:

  private val PartitionFanout     = 16
  private val BatchRecords        = 256
  private val MaxEncodedKeyBytes  = 512
  private val TempDirectoryPrefix = "graviton-gc-"

  final case class Config(
    maxReferencesPerPartition: Int,
    maximumPartitionDepth: Int,
  ):
    def validate: Either[String, Config] =
      for
        _ <- Either.cond(
               maxReferencesPerPartition >= 1,
               (),
               "maxReferencesPerPartition must be at least 1",
             )
        _ <- Either.cond(
               maximumPartitionDepth >= 1 && maximumPartitionDepth <= 128,
               (),
               "maximumPartitionDepth must be within 1..128",
             )
      yield this

  final case class InventoryRecord(
    entry: BlockInventoryEntry
  )

  final case class InventoryCapture(
    path: Path,
    scannedBlocks: Long,
    eligibleBlocks: Long,
  )

  final case class ReferenceCapture(
    path: Path,
    referenceEntries: Long,
  )

  final case class CandidateCapture(
    path: Path,
    counts: Counts,
  )

  final case class Counts(
    blocks: Long,
    bytes: Long,
  ):
    def add(entry: BlockInventoryEntry): Counts =
      Counts(
        blocks = java.lang.Math.addExact(blocks, 1L),
        bytes = java.lang.Math.addExact(bytes, entry.size),
      )

  object Counts:
    val empty: Counts = Counts(0L, 0L)

  final class Workspace(
    val root: Path,
    config: Config,
  ):

    def writeReferences(
      name: String,
      references: ZStream[Any, Throwable, BinaryKey.Block],
    ): Task[ReferenceCapture] =
      val path = root.resolve(s"$name.references")
      withWriter(path, ReferenceCodec) { writer =>
        references
          .rechunk(BatchRecords)
          .chunks
          .runFoldZIO(0L) { (count, batch) =>
            ZIO.attemptBlocking {
              batch.foreach(writer.write)
              java.lang.Math.addExact(count, batch.length.toLong)
            }
          }
      }.map(ReferenceCapture(path, _))

    def writeInventory(
      name: String,
      inventory: ZStream[Any, Throwable, BlockInventoryEntry],
      oldestEligible: Instant,
    ): Task[InventoryCapture] =
      val path = root.resolve(s"$name.inventory")
      withWriter(path, InventoryCodec) { writer =>
        inventory
          .rechunk(BatchRecords)
          .chunks
          .runFoldZIO(InventoryCapture(path, 0L, 0L)) { (state, batch) =>
            ZIO.attemptBlocking {
              var scanned  = state.scannedBlocks
              var eligible = state.eligibleBlocks
              batch.foreach { entry =>
                if entry.size < 0L then
                  throw new IllegalArgumentException(s"Block inventory reported a negative size for ${entry.key.bits.render}")
                scanned = java.lang.Math.addExact(scanned, 1L)
                if !entry.lastModified.isAfter(oldestEligible) then
                  writer.write(InventoryRecord(entry))
                  eligible = java.lang.Math.addExact(eligible, 1L)
              }
              InventoryCapture(path, scanned, eligible)
            }
          }
      }

    /** Invoke `onUnreferenced` for every exact non-member without collecting candidates. */
    def visitUnreferenced(
      references: ReferenceCapture,
      inventory: Path,
    )(
      onUnreferenced: BlockInventoryEntry => Task[Unit]
    ): Task[Counts] =
      visitPartition(references.path, inventory, depth = 0)(onUnreferenced)

    /** Spill the exact candidate set so the re-mark pass never has to rescan the backend inventory. */
    def captureUnreferenced(
      name: String,
      references: ReferenceCapture,
      inventory: InventoryCapture,
    ): Task[CandidateCapture] =
      val path = root.resolve(s"$name.candidates")
      withWriter(path, InventoryCodec) { writer =>
        visitUnreferenced(references, inventory.path) { entry =>
          ZIO.attemptBlocking(writer.write(InventoryRecord(entry)))
        }
      }.map(CandidateCapture(path, _))

    /**
     * Load a small compatibility receipt only after the caller has checked the
     * bounded reference count. This is never used by the production sweep API.
     */
    def distinctReferencesWithin(limit: Int, references: ReferenceCapture): Task[Set[BinaryKey.Block]] =
      ZIO.attemptBlocking {
        val reader = Reader.open(references.path, ReferenceCodec)
        try
          val result = mutable.HashSet.empty[BinaryKey.Block]
          var next   = reader.next()
          while next.nonEmpty do
            result += next.get
            if result.size > limit then
              throw new IllegalArgumentException(
                s"Compatibility collection exceeds $limit distinct references; use GarbageCollector.sweep instead"
              )
            next = reader.next()
          result.toSet
        finally reader.close()
      }

    private def visitPartition(
      references: Path,
      inventory: Path,
      depth: Int,
    )(
      onUnreferenced: BlockInventoryEntry => Task[Unit]
    ): Task[Counts] =
      countReferencesAtMost(references, config.maxReferencesPerPartition + 1).flatMap { count =>
        if count <= config.maxReferencesPerPartition then
          loadReferenceSet(references).flatMap { marked =>
            streamInventory(inventory).runFoldZIO(Counts.empty) { (counts, record) =>
              if marked.contains(record.entry.key) then ZIO.succeed(counts)
              else onUnreferenced(record.entry).as(counts.add(record.entry))
            }
          }
        else if depth < config.maximumPartitionDepth then
          withPartitionDirectories { (referenceDir, inventoryDir) =>
            for
              referenceParts <- partitionReferences(references, referenceDir, depth)
              inventoryParts <- partitionInventory(inventory, inventoryDir, depth)
              counts         <- ZIO.foldLeft(referenceParts.zip(inventoryParts))(Counts.empty) { case (acc, (referencePart, inventoryPart)) =>
                                  visitPartition(referencePart, inventoryPart, depth + 1)(onUnreferenced).map { current =>
                                    Counts(
                                      java.lang.Math.addExact(acc.blocks, current.blocks),
                                      java.lang.Math.addExact(acc.bytes, current.bytes),
                                    )
                                  }
                                }
            yield counts
          }
        else
          // A malicious workload can force a common digest prefix. Keep the
          // collector exact and bounded by scanning the final reference file
          // per candidate instead of allocating a larger set.
          streamInventory(inventory).runFoldZIO(Counts.empty) { (counts, record) =>
            containsReference(references, record.entry.key).flatMap { referenced =>
              if referenced then ZIO.succeed(counts)
              else onUnreferenced(record.entry).as(counts.add(record.entry))
            }
          }
      }

    private def countReferencesAtMost(path: Path, limit: Int): Task[Int] =
      ZIO.attemptBlocking {
        val reader = Reader.open(path, ReferenceCodec)
        try
          var count = 0
          while count < limit && reader.next().nonEmpty do count += 1
          count
        finally reader.close()
      }

    private def loadReferenceSet(path: Path): Task[Set[BinaryKey.Block]] =
      ZIO.attemptBlocking {
        val reader = Reader.open(path, ReferenceCodec)
        try
          val result = mutable.HashSet.empty[BinaryKey.Block]
          var next   = reader.next()
          while next.nonEmpty do
            result += next.get
            if result.size > config.maxReferencesPerPartition then
              throw new IllegalStateException(
                s"Reference partition exceeded ${config.maxReferencesPerPartition} entries after bounded count"
              )
            next = reader.next()
          result.toSet
        finally reader.close()
      }

    private def containsReference(path: Path, key: BinaryKey.Block): Task[Boolean] =
      ZIO.attemptBlocking {
        val reader = Reader.open(path, ReferenceCodec)
        try
          var found = false
          var next  = reader.next()
          while !found && next.nonEmpty do
            found = next.get == key
            next = if found then None else reader.next()
          found
        finally reader.close()
      }

    private def partitionReferences(source: Path, destination: Path, depth: Int): Task[Vector[Path]] =
      partition(source, destination, depth, ReferenceCodec)(identity)

    private def partitionInventory(source: Path, destination: Path, depth: Int): Task[Vector[Path]] =
      partition(source, destination, depth, InventoryCodec)(_.entry.key)

    private def partition[A](
      source: Path,
      destination: Path,
      depth: Int,
      codec: RecordCodec[A],
    )(
      keyOf: A => BinaryKey.Block
    ): Task[Vector[Path]] =
      for
        _    <- ZIO.attemptBlocking(Files.createDirectories(destination))
        paths = Vector.tabulate(PartitionFanout)(bucket => destination.resolve(f"$bucket%02x.records"))
        _    <- withPartitionWriters(paths, codec) { writers =>
                  stream(source, codec)
                    .rechunk(BatchRecords)
                    .chunks
                    .runForeach { batch =>
                      ZIO.attemptBlocking {
                        batch.foreach(record => writers.write(bucketFor(keyOf(record), depth), record))
                      }
                    }
                }
      yield paths

    private def newPartitionDirectory(kind: String): Task[Path] =
      ZIO.attemptBlocking(Files.createTempDirectory(root, s"$kind-"))

    private def withPartitionDirectories[A](use: (Path, Path) => Task[A]): Task[A] =
      ZIO.acquireReleaseWith(
        for
          references <- newPartitionDirectory("references")
          inventory  <- newPartitionDirectory("inventory")
        yield references -> inventory
      )(directories =>
        ZIO.attemptBlocking {
          deleteTree(directories._1)
          deleteTree(directories._2)
        }.ignore
      )(directories => use(directories._1, directories._2))

  def scoped[A](config: Config, workspaceDirectory: Option[Path])(use: Workspace => Task[A]): Task[A] =
    for
      valid <- ZIO.fromEither(config.validate).mapError(new IllegalArgumentException(_))
      value <- ZIO.acquireReleaseWith(
                 ZIO
                   .attemptBlocking {
                     val parent = workspaceDirectory match
                       case None        => None
                       case Some(value) =>
                         Files.createDirectories(value)
                         Some(value)
                     parent match
                       case None        => Files.createTempDirectory(TempDirectoryPrefix)
                       case Some(value) => Files.createTempDirectory(value, TempDirectoryPrefix)
                   }
                   .map(new Workspace(_, valid))
               )(workspace => ZIO.attemptBlocking(deleteTree(workspace.root)).ignore)(use)
    yield value

  private def stream[A](path: Path, codec: RecordCodec[A]): ZStream[Any, Throwable, A] =
    ZStream
      .acquireReleaseWith(ZIO.attemptBlocking(Reader.open(path, codec)))(reader => ZIO.attemptBlocking(reader.close()).orDie)
      .flatMap(reader => ZStream.unfoldZIO(reader)(current => ZIO.attemptBlocking(current.next().map(_ -> current))))

  private def streamInventory(path: Path): ZStream[Any, Throwable, InventoryRecord] =
    stream(path, InventoryCodec)

  private def withWriter[A, B](path: Path, codec: RecordCodec[A])(use: Writer[A] => Task[B]): Task[B] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking(Writer.open(path, codec)))(writer => ZIO.attemptBlocking(writer.close()).orDie)(use)

  private def withPartitionWriters[A, B](paths: Vector[Path], codec: RecordCodec[A])(
    use: PartitionWriters[A] => Task[B]
  ): Task[B] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking(PartitionWriters.open(paths, codec)))(writers => ZIO.attemptBlocking(writers.close()).orDie)(
      use
    )

  private def bucketFor(key: BinaryKey.Block, depth: Int): Int =
    val digest = key.bits.digest.bytes
    val byte   = depth / 2
    if byte >= digest.length then 0
    else
      val value = digest(byte) & 0xff
      if depth % 2 == 0 then value >>> 4 else value & 0x0f

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val _ = Files.walkFileTree(
        root,
        new SimpleFileVisitor[Path]:
          override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult =
            Files.deleteIfExists(file)
            FileVisitResult.CONTINUE

          override def postVisitDirectory(directory: Path, error: java.io.IOException | Null): FileVisitResult =
            if error != null then throw error
            Files.deleteIfExists(directory)
            FileVisitResult.CONTINUE,
      )

  private trait RecordCodec[A]:
    def write(output: DataOutputStream, value: A): Unit
    def read(input: DataInputStream): Option[A]

  private object ReferenceCodec extends RecordCodec[BinaryKey.Block]:
    override def write(output: DataOutputStream, value: BinaryKey.Block): Unit = writeKey(output, value)

    override def read(input: DataInputStream): Option[BinaryKey.Block] = readKey(input)

  private object InventoryCodec extends RecordCodec[InventoryRecord]:
    override def write(output: DataOutputStream, value: InventoryRecord): Unit =
      writeKey(output, value.entry.key)
      output.writeLong(value.entry.size)
      output.writeLong(value.entry.lastModified.toEpochMilli)

    override def read(input: DataInputStream): Option[InventoryRecord] =
      readKey(input).map { key =>
        val size         = input.readLong()
        if size < 0L then throw new IllegalArgumentException(s"Spilled block inventory has negative size for ${key.bits.render}")
        val lastModified = Instant.ofEpochMilli(input.readLong())
        InventoryRecord(BlockInventoryEntry(key, size, lastModified))
      }

  private def writeKey(output: DataOutputStream, key: BinaryKey.Block): Unit =
    val bytes = key.bits.render.getBytes(StandardCharsets.US_ASCII)
    if bytes.isEmpty || bytes.length > MaxEncodedKeyBytes then
      throw new IllegalArgumentException(
        s"Block key encoding is outside 1..$MaxEncodedKeyBytes bytes: ${key.bits.render}"
      )
    output.writeInt(bytes.length)
    output.write(bytes)

  private def readKey(input: DataInputStream): Option[BinaryKey.Block] =
    readLength(input).map { length =>
      val bytes = new Array[Byte](length)
      input.readFully(bytes)
      val text  = new String(bytes, StandardCharsets.US_ASCII)
      KeyBits
        .fromString(text)
        .flatMap(BinaryKey.block)
        .fold(message => throw new IllegalArgumentException(s"Invalid spilled block key: $message"), identity)
    }

  private def readLength(input: DataInputStream): Option[Int] =
    val first = input.read()
    if first == -1 then None
    else
      val second = input.read()
      val third  = input.read()
      val fourth = input.read()
      if second == -1 || third == -1 || fourth == -1 then throw new EOFException("Truncated garbage-collection spool record length")
      val length = (first << 24) | (second << 16) | (third << 8) | fourth
      if length <= 0 || length > MaxEncodedKeyBytes then
        throw new IllegalArgumentException(s"Garbage-collection spool key length $length is outside 1..$MaxEncodedKeyBytes")
      Some(length)

  private final class Writer[A] private (
    output: DataOutputStream,
    codec: RecordCodec[A],
  ):
    def write(value: A): Unit = codec.write(output, value)
    def close(): Unit         = output.close()

  private object Writer:
    def open[A](path: Path, codec: RecordCodec[A]): Writer[A] =
      Files.createDirectories(path.getParent)
      val output = new DataOutputStream(
        new BufferedOutputStream(
          Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        )
      )
      new Writer(output, codec)

  private final class Reader[A] private (
    input: DataInputStream,
    codec: RecordCodec[A],
  ):
    def next(): Option[A] = codec.read(input)
    def close(): Unit     = input.close()

  private object Reader:
    def open[A](path: Path, codec: RecordCodec[A]): Reader[A] =
      val input = new DataInputStream(
        new BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ))
      )
      new Reader(input, codec)

  private final class PartitionWriters[A] private (
    writers: Vector[Writer[A]]
  ):
    def write(bucket: Int, value: A): Unit = writers(bucket).write(value)
    def close(): Unit                      = writers.foreach(_.close())

  private object PartitionWriters:
    def open[A](paths: Vector[Path], codec: RecordCodec[A]): PartitionWriters[A] =
      val opened = mutable.ArrayBuffer.empty[Writer[A]]
      try
        paths.foreach(path => opened += Writer.open(path, codec))
        new PartitionWriters(opened.toVector)
      catch
        case error: Throwable =>
          opened.foreach { writer =>
            try writer.close()
            catch case _: Throwable => ()
          }
          throw error

end GarbageCollectionSpool
