package torrent
package chunking

import java.security.MessageDigest

import scala.language.implicitConversions

import torrent.collections.ByteSlidingWindow

import zio.*
import zio.schema.{ Schema, derived }
import zio.stream.*

/**
 * Content-defined chunking using rolling hash (Rabin fingerprinting)
 *
 * This implementation uses a polynomial rolling hash to create deterministic
 * chunk boundaries. Identical content will always produce identical chunks,
 * making it perfect for deduplication.
 */
object RollingHashChunker:
  self =>

  /**
   * Configuration for rolling hash chunking
   */
  case class Config(
    val minSize:                                Length = `4KB`,            // 4KB min
    val avgSize:                                Length = `64KB`,           // 64KB avg
    val maxSize:                                Length = `256KB`,          // 256KB max
    private[RollingHashChunker] val windowSize: Int = 48,                  // Rolling hash window
    private[RollingHashChunker] val polynomial: Long = 0x3da3358b4dc173L,  // Rabin polynomial
    val hashAlgo:                               HashAlgo = HashAlgo.Blake3 // For final chunk hash
  ) derives Schema:
    // Calculate the mask for boundary detection based on average size
    // More bits = larger average chunk size
    val maskBits: Int = math.max(1, (math.log(avgSize.value.toDouble) / math.log(2.0)).toInt - 12)
    val mask: Long    = (1L << maskBits) - 1

  object Config:
    given default: Config = Config()

  /**
   * Chunk a stream using rolling hash algorithm
   */
  def chunk(
    stream: ZStream[Any, Throwable, Byte]
  )(using Config): ZStream[Any, Throwable, FileChunk] =
    stream.via(pipeline)

  /**
   * ZPipeline that performs content-defined chunking using ZChannel
   */
  def pipeline(using Config): ZPipeline[Any, Throwable, Byte, FileChunk] =
    ZPipeline.fromChannel(chunkingChannel)

  /**
   * Low-level ZChannel for stateful chunking
   */
  private def chunkingChannel(using
    config: Config
  ): ZChannel[Any, Throwable, Chunk[Byte], Any, Throwable, Chunk[FileChunk], Any] =
    def loop(state: ChunkingState): ZChannel[Any, Throwable, Chunk[Byte], Any, Throwable, Chunk[FileChunk], Any] =
      ZChannel.readWithCause(
        // On input chunk
        (inputChunk: Chunk[Byte]) =>
          ZChannel.fromZIO(processChunk(state, inputChunk)).flatMap { case (newState, outputChunks) =>
            if outputChunks.nonEmpty then ZChannel.write(outputChunks) *> loop(newState)
            else loop(newState)
          },
        // On failure
        (cause: Cause[Throwable]) => ZChannel.failCause(cause),
        // On end of input - emit final chunk if buffer has data
        (_: Any) =>
          if state.buffer.nonEmpty then
            ZChannel.fromZIO(state.emitFinalChunk).flatMap { chunk =>
              ZChannel.write(Chunk.single(chunk))
            }
          else ZChannel.unit
      )

    loop(ChunkingState.initial)

  /**
   * Process a chunk of input bytes and return new state plus any output chunks
   */
  private def processChunk(
    state:      ChunkingState,
    inputChunk: Chunk[Byte]
  ): UIO[(ChunkingState, Chunk[FileChunk])] =
    ZIO.succeed {
      inputChunk.foldLeft((state, Chunk.empty[FileChunk])) { case ((state, outputChunks), byte) =>
        val newState = state.addByte(byte)
        if newState.shouldEmitChunk then
          val (newerState, chunk) = newState.emitChunk
          (newerState, outputChunks :+ chunk)
        else (newState, outputChunks)
      }
      inputChunk.foldLeft((state, Chunk.empty[FileChunk])) { case ((state, outputChunks), byte) =>
        val newState = state.addByte(byte)
        if newState.shouldEmitChunk then
          val (newerState, chunk) = newState.emitChunk
          (newerState, outputChunks :+ chunk)
        else (newState, outputChunks)
      }
    }

  /**
   * Internal state for the chunking process
   */
  private case class ChunkingState(
    config:        Config,
    window:        ByteSlidingWindow,
    buffer:        Chunk[Byte],
    currentOffset: Index,
    digest:        MessageDigest
  ):

    def addByte(byte: Byte): ChunkingState =
      val newWindow = window.add(byte)
      val newBuffer = buffer :+ byte
      digest.update(byte)

      copy(
        window = newWindow,
        buffer = newBuffer
      )

    def shouldEmitChunk: Boolean =
      if buffer.size < config.minSize.value.toInt then false
      else if buffer.size >= config.maxSize.value.toInt then true
      else if window.isFull then (window.hash & config.mask) == 0
      else false

    def emitChunk: (ChunkingState, FileChunk) =
      val chunkBytes = Bytes.either(buffer).getOrElse(Bytes.applyUnsafe(Chunk.empty))

      // Create content-addressable key from hash
      val hashBytes = digest.digest()
      val key       = Bytes
        .either(Chunk.fromArray(hashBytes))
        .map(_.computeHashKey(config.hashAlgo))
        .getOrElse(BinaryKey.Static(s"chunk-${currentOffset.value}"))

      val chunk = FileChunk(
        data = chunkBytes,
        offset = currentOffset,
        key = key
      )

      val newState = copy(
        buffer = Chunk.empty,
        currentOffset = Index.applyUnsafe(currentOffset.value + buffer.size.toLong),
        window = ByteSlidingWindow(config.windowSize, config.polynomial),
        digest = config.hashAlgo.getInstance
      )

      (newState, chunk)

    def emitFinalChunk: UIO[FileChunk] =
      ZIO.succeed {
        val (_, chunk) = emitChunk
        chunk
      }

  private object ChunkingState:
    def initial(using config: Config): ChunkingState =
      ChunkingState(
        config = config,
        window = ByteSlidingWindow(config.windowSize, config.polynomial),
        buffer = Chunk.empty,
        currentOffset = Index(0L),
        digest = config.hashAlgo.getInstance
      )

  /**
   * Convenience method to chunk bytes directly
   */
  def chunkBytes(
    bytes: Bytes
  )(using Config): IO[Throwable, List[FileChunk]] =
    if bytes.isEmpty then ZIO.succeed(List.empty)
    else
      ZStream
        .fromChunk(Chunk.fromArray(bytes.toArray))
        .via(pipeline)
        .runCollect
        .map(_.toList)

  /**
   * Calculate rolling hash for a byte sequence (for testing/verification)
   */
  def calculateRollingHash(
    bytes:      Bytes,
    windowSize: Int = 48,
    polynomial: Long = 0x3da3358b4dc173L
  ): Long =
    val window = ByteSlidingWindow(windowSize, polynomial)
    bytes.toArray.foldLeft(window)(_.add(_)).hash

  /**
   * Verify chunk boundaries are deterministic
   */
  def verifyDeterminism(
    bytes1: Bytes,
    bytes2: Bytes
  )(using Config): IO[Throwable, Boolean] =
    for
      chunks1 <- chunkBytes(bytes1)
      chunks2 <- chunkBytes(bytes2)
    yield chunks1.map(_.key) == chunks2.map(_.key)

  /**
   * Calculate deduplication ratio between two byte sequences
   */
  def calculateDeduplicationRatio(
    original: Bytes,
    modified: Bytes
  )(using Config): IO[Throwable, Double] =
    for
      originalChunks <- chunkBytes(original)
      modifiedChunks <- chunkBytes(modified)
      originalKeys    = originalChunks.map(_.key).toSet
      modifiedKeys    = modifiedChunks.map(_.key).toSet
      commonKeys      = originalKeys.intersect(modifiedKeys)
      ratio           = if originalKeys.nonEmpty then commonKeys.size.toDouble / originalKeys.size.toDouble
                        else 1.0
    yield ratio
