package graviton.runtime.stores

import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.ManifestEntry
import graviton.core.ranges.Span
import graviton.core.types.BlobOffset
import graviton.runtime.model.BlockManifestEntry
import zio.{Chunk, ChunkBuilder, Scope, Task, ZIO}
import zio.stream.ZStream

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream, EOFException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicBoolean

/** Disk-backed manifest staging so arbitrary-size payloads never retain all entries in memory. */
private[stores] final class ManifestSpool private (
  private val path: Path,
  private val output: DataOutputStream,
):
  private val closed     = new AtomicBoolean(false)
  private var nextIndex  = 0L
  private var nextOffset = 0L

  def append(entry: BlockManifestEntry): Task[Unit] =
    ZIO.attemptBlocking {
      if closed.get() then throw new IllegalStateException("Manifest spool is closed")
      if nextIndex >= BlobManifestRepo.MaxEntries.toLong then
        throw new IllegalArgumentException(s"Manifest exceeds ${BlobManifestRepo.MaxEntries} entries")
      if entry.index.value != nextIndex then
        throw new IllegalArgumentException(
          s"Manifest entry index ${entry.index.value} is not the expected $nextIndex"
        )
      if entry.offset.value != nextOffset then
        throw new IllegalArgumentException(
          s"Manifest entry offset ${entry.offset.value} is not the expected $nextOffset"
        )

      val rendered = entry.key.bits.render.getBytes(StandardCharsets.US_ASCII)
      if rendered.isEmpty || rendered.length > ManifestSpool.MaxKeyBytes then
        throw new IllegalArgumentException(
          s"Manifest block key length ${rendered.length} is outside 1..${ManifestSpool.MaxKeyBytes} bytes"
        )

      output.writeInt(rendered.length)
      output.write(rendered)
      output.writeLong(entry.offset.value)
      output.writeInt(entry.size.value)
      nextIndex += 1L
      nextOffset = java.lang.Math.addExact(nextOffset, entry.size.value.toLong)
    }

  def finish(): Task[ManifestSpool.Summary] =
    ZIO.attemptBlocking {
      closeOutput()
      ManifestSpool.Summary(nextIndex.toInt, nextOffset)
    }

  def entries: ZStream[Any, Throwable, ManifestEntry] =
    ZStream
      .acquireReleaseWith(ZIO.attemptBlocking(ManifestSpool.Reader.open(path)))(reader => ZIO.attemptBlocking(reader.close()).orDie)
      .flatMap(reader =>
        ZStream.unfoldChunkZIO(reader) { current =>
          ZIO.attemptBlocking(current.readBatch()).map(_.map(batch => batch -> current))
        }
      )

  private[stores] def closeAndDelete: Task[Unit] =
    ZIO.attemptBlocking {
      closeOutput()
      val _ = Files.deleteIfExists(path)
      ()
    }

  private def closeOutput(): Unit =
    if closed.compareAndSet(false, true) then output.close()

private[stores] object ManifestSpool:
  private val MaxKeyBytes      = 512
  private val ReadBatchEntries = 256

  final case class Summary(blockCount: Int, totalSize: Long)

  def scoped: ZIO[Scope, Throwable, ManifestSpool] =
    ZIO.acquireRelease {
      ZIO.attemptBlocking {
        val path   = Files.createTempFile("graviton-manifest-", ".spool")
        val output = new DataOutputStream(
          new BufferedOutputStream(
            Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
          )
        )
        new ManifestSpool(path, output)
      }
    }(_.closeAndDelete.orDie)

  private final class Reader private (input: DataInputStream):
    private var index          = 0L
    private var expectedOffset = 0L
    private var complete       = false

    def readBatch(): Option[Chunk[ManifestEntry]] =
      if complete then None
      else
        val builder = ChunkBuilder.make[ManifestEntry]()
        var read    = 0
        while read < ReadBatchEntries && !complete do
          readEntry() match
            case None        => complete = true
            case Some(entry) =>
              builder += entry
              read += 1
        val result  = builder.result()
        Option.when(result.nonEmpty)(result)

    def close(): Unit = input.close()

    private def readEntry(): Option[ManifestEntry] =
      val keyLength =
        try input.readInt()
        catch case _: EOFException => return None

      if keyLength <= 0 || keyLength > MaxKeyBytes then
        throw new IllegalArgumentException(
          s"Manifest spool key length $keyLength is outside 1..$MaxKeyBytes bytes"
        )
      val keyBytes = input.readNBytes(keyLength)
      if keyBytes.length != keyLength then throw new EOFException("Unexpected end of manifest spool block key")
      val key      = KeyBits
        .fromString(new String(keyBytes, StandardCharsets.US_ASCII))
        .flatMap(BinaryKey.block)
        .fold(message => throw new IllegalArgumentException(message), identity)
      val offset   = input.readLong()
      val length   = input.readInt()

      if offset != expectedOffset then
        throw new IllegalArgumentException(s"Manifest spool entry $index starts at $offset, expected $expectedOffset")
      if length <= 0 || length.toLong != key.bits.size then
        throw new IllegalArgumentException(
          s"Manifest spool entry $index length $length does not match block size ${key.bits.size}"
        )

      val start = BlobOffset.either(offset).fold(message => throw new IllegalArgumentException(message), identity)
      val end   = BlobOffset
        .either(java.lang.Math.addExact(offset, length.toLong - 1L))
        .fold(message => throw new IllegalArgumentException(message), identity)
      val span  = Span.make(start, end).fold(message => throw new IllegalArgumentException(message), identity)

      index += 1L
      expectedOffset = java.lang.Math.addExact(expectedOffset, length.toLong)
      Some(ManifestEntry(key, span, Map.empty))

  private object Reader:
    def open(path: Path): Reader =
      new Reader(
        new DataInputStream(
          new BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ))
        )
      )

end ManifestSpool
