package graviton.runtime.stores

import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.manifest.ManifestEntry
import graviton.core.ranges.Span
import graviton.core.types.{BlobOffset, FileSize}
import zio.{Chunk, ChunkBuilder, Task, ZIO}
import zio.stream.ZStream

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream, EOFException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * Incremental filesystem manifest format used for large CAS blobs.
 *
 * The format deliberately stores only the semantic fields used by CAS
 * reconstruction. It has a fixed-size header and length-delimited block keys,
 * so readers can stream entries without loading the manifest file.
 */
private[stores] object StreamingManifestFile:

  private val Magic: Array[Byte] = Array('G'.toByte, 'V'.toByte, 'M'.toByte, '2'.toByte)
  private val MaxKeyBytes        = 512
  private val ReadBatchEntries   = 256

  final case class Header(totalSize: FileSize, blockCount: Int)

  def readHeader(path: Path): Task[Header] =
    ZIO.attemptBlocking {
      val input = openInput(path)
      try readHeaderFrom(input)
      finally input.close()
    }

  def streamEntries(path: Path): ZStream[Any, Throwable, ManifestEntry] =
    ZStream
      .acquireReleaseWith(ZIO.attemptBlocking(Reader.open(path)))(reader => ZIO.attemptBlocking(reader.close()).orDie)
      .flatMap(reader =>
        ZStream.unfoldChunkZIO(reader) { current =>
          ZIO.attemptBlocking(current.readBatch()).map(_.map(chunk => chunk -> current))
        }
      )

  final class Writer private (
    private val output: DataOutputStream,
    val header: Header,
  ):
    private var observedCount  = 0
    private var observedOffset = 0L
    private var finished       = false

    def writeBatch(entries: Chunk[ManifestEntry]): Unit =
      ensureOpen()
      entries.foreach(writeEntry)

    def finish(): Unit =
      ensureOpen()
      if observedCount != header.blockCount then
        throw new IllegalArgumentException(
          s"Manifest entry count mismatch: expected ${header.blockCount}, observed $observedCount"
        )
      if observedOffset != header.totalSize.value then
        throw new IllegalArgumentException(
          s"Manifest size mismatch: expected ${header.totalSize.value}, observed $observedOffset"
        )
      output.flush()
      finished = true

    def close(): Unit = output.close()

    private def writeEntry(entry: ManifestEntry): Unit =
      if observedCount >= header.blockCount then
        throw new IllegalArgumentException(s"Manifest contains more than ${header.blockCount} declared entries")
      if entry.annotations.nonEmpty then
        throw new IllegalArgumentException("Streaming CAS manifests do not permit non-semantic entry annotations")

      val block = entry.key match
        case value: BinaryKey.Block => value
        case other                  =>
          throw new IllegalArgumentException(s"CAS manifest entry must reference a block key, got $other")

      val start  = entry.span.startInclusive.value
      val length = entry.span.endInclusive.value - start + 1L
      if start != observedOffset then
        throw new IllegalArgumentException(
          s"Manifest entry $observedCount starts at $start, expected $observedOffset"
        )
      if length != block.bits.size || length <= 0L || length > Int.MaxValue.toLong then
        throw new IllegalArgumentException(
          s"Manifest entry $observedCount length $length does not match block size ${block.bits.size}"
        )

      val rendered = block.bits.render.getBytes(StandardCharsets.US_ASCII)
      if rendered.isEmpty || rendered.length > MaxKeyBytes then
        throw new IllegalArgumentException(
          s"Manifest block key length ${rendered.length} is outside 1..$MaxKeyBytes bytes"
        )

      output.writeInt(rendered.length)
      output.write(rendered)
      output.writeLong(start)
      output.writeInt(length.toInt)
      observedCount += 1
      observedOffset = java.lang.Math.addExact(observedOffset, length)

    private def ensureOpen(): Unit =
      if finished then throw new IllegalStateException("Streaming manifest writer is already finished")

  object Writer:
    def open(path: Path, header: Header): Writer =
      validateHeader(header)
      val output = new DataOutputStream(
        new BufferedOutputStream(
          Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        )
      )
      try
        output.write(Magic)
        output.writeLong(header.totalSize.value)
        output.writeInt(header.blockCount)
        new Writer(output, header)
      catch
        case error: Throwable =>
          output.close()
          throw error

  private final class Reader private (
    private val input: DataInputStream,
    val header: Header,
  ):
    private var index          = 0
    private var expectedOffset = 0L
    private var complete       = false

    def readBatch(): Option[Chunk[ManifestEntry]] =
      if complete then None
      else if index >= header.blockCount then
        validateEnd()
        complete = true
        None
      else
        val count   = math.min(ReadBatchEntries, header.blockCount - index)
        val builder = ChunkBuilder.make[ManifestEntry]()
        var read    = 0
        while read < count do
          builder += readEntry()
          read += 1
        Some(builder.result())

    def close(): Unit = input.close()

    private def readEntry(): ManifestEntry =
      val keyLength = input.readInt()
      if keyLength <= 0 || keyLength > MaxKeyBytes then
        throw new IllegalArgumentException(
          s"Manifest block key length $keyLength is outside 1..$MaxKeyBytes bytes"
        )
      val keyBytes  = input.readNBytes(keyLength)
      if keyBytes.length != keyLength then throw new EOFException("Unexpected end of manifest block key")
      val keyText   = new String(keyBytes, StandardCharsets.US_ASCII)
      val block     = KeyBits
        .fromString(keyText)
        .flatMap(BinaryKey.block)
        .fold(message => throw new IllegalArgumentException(message), identity)
      val offset    = input.readLong()
      val length    = input.readInt()

      if offset != expectedOffset then
        throw new IllegalArgumentException(
          s"Manifest entry $index starts at $offset, expected $expectedOffset"
        )
      if length <= 0 || length.toLong != block.bits.size then
        throw new IllegalArgumentException(
          s"Manifest entry $index length $length does not match block size ${block.bits.size}"
        )

      val start = BlobOffset.either(offset).fold(message => throw new IllegalArgumentException(message), identity)
      val end   = BlobOffset
        .either(java.lang.Math.addExact(offset, length.toLong - 1L))
        .fold(message => throw new IllegalArgumentException(message), identity)
      val span  = Span.make(start, end).fold(message => throw new IllegalArgumentException(message), identity)

      index += 1
      expectedOffset = java.lang.Math.addExact(expectedOffset, length.toLong)
      ManifestEntry(block, span, Map.empty)

    private def validateEnd(): Unit =
      if expectedOffset != header.totalSize.value then
        throw new IllegalArgumentException(
          s"Manifest size mismatch: expected ${header.totalSize.value}, observed $expectedOffset"
        )
      if input.read() != -1 then throw new IllegalArgumentException("Manifest contains trailing bytes")

  private object Reader:
    def open(path: Path): Reader =
      val input = openInput(path)
      try new Reader(input, readHeaderFrom(input))
      catch
        case error: Throwable =>
          input.close()
          throw error

  private def openInput(path: Path): DataInputStream =
    new DataInputStream(new BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ)))

  private def readHeaderFrom(input: DataInputStream): Header =
    val magic      = input.readNBytes(Magic.length)
    if !java.util.Arrays.equals(magic, Magic) then throw new IllegalArgumentException("Not a Graviton streaming manifest")
    val totalSize  = FileSize
      .either(input.readLong())
      .fold(message => throw new IllegalArgumentException(message), identity)
    val blockCount = input.readInt()
    val header     = Header(totalSize, blockCount)
    validateHeader(header)
    header

  private def validateHeader(header: Header): Unit =
    if header.blockCount <= 0 || header.blockCount > BlobManifestRepo.MaxEntries then
      throw new IllegalArgumentException(
        s"Manifest block count ${header.blockCount} is outside 1..${BlobManifestRepo.MaxEntries}"
      )

end StreamingManifestFile
