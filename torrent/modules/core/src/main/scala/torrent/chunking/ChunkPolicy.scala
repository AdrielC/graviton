package torrent.chunking

import scala.language.implicitConversions

import torrent.{ Bytes, Length }

import zio.*
import zio.stream.*

/**
 * Unified interface for chunking strategies.
 *
 * ChunkPolicy defines how binary streams should be split into chunks,
 * supporting various strategies from simple size-based splitting to
 * content-aware semantic boundaries.
 */
trait ChunkPolicy:
  /**
   * Apply this chunking policy to a stream of bytes
   */
  def apply(stream: ZStream[Any, Throwable, Byte]): ZStream[Any, Throwable, Bytes] =
    stream.via(pipeline)

  /**
   * Create a pipeline for this chunking policy
   */
  def pipeline: ZPipeline[Any, Throwable, Byte, Bytes]

object ChunkPolicy:

  /**
   * Rolling hash chunking using Rabin-Karp algorithm
   */
  def rollingHash(
    minSize: Length = `32KB`,
    avgSize: Length = `64KB`,
    maxSize: Length = `256KB`
  ): ChunkPolicy = new ChunkPolicy:

    given config: RollingHashChunker.Config = RollingHashChunker.Config(minSize, avgSize, maxSize)

    override def apply(stream: ZStream[Any, Throwable, Byte]): ZStream[Any, Throwable, Bytes] =
      RollingHashChunker.chunk(stream).map(_.data)

    def pipeline: ZPipeline[Any, Throwable, Byte, Bytes] =
      RollingHashChunker.pipeline.map(_.data)

  /**
   * Fixed-size chunking (fallback for opaque content)
   */
  def fixedSize(chunkSize: Length): ChunkPolicy = new ChunkPolicy:

    def pipeline: ZPipeline[Any, Throwable, Byte, Bytes] =
      ZPipeline
        .rechunk(chunkSize.toInt)
        .chunks
        .mapZIO(chunk => ZIO.fromEither(Bytes.either(chunk)).mapError(Throwable(_)))

  /**
   * PDF-aware chunking that respects stream boundaries
   */
  def pdfAware(
    respectStreams: Boolean = true,
    maxChunkSize:   Length = Length(1024 * 1024)
  ): ChunkPolicy = new ChunkPolicy:

    def pipeline: ZPipeline[Any, Throwable, Byte, Bytes] =
      if respectStreams then PdfAwareChunker.pipeline(maxChunkSize)
      else rollingHash(maxSize = maxChunkSize).pipeline

  /**
   * Token-aware chunking for structured data
   */
  def tokenAware(
    tokens:       Set[String] = Set("}", "endstream", "\n"),
    maxChunkSize: Length = Length(512 * 1024)
  ): ChunkPolicy = new ChunkPolicy:

    def pipeline: ZPipeline[Any, Throwable, Byte, Bytes] =
      TokenAwareChunker.pipeline(tokens, maxChunkSize)

  /**
   * Hybrid policy combining rolling hash with semantic awareness
   */
  def hybrid(
    primary:   ChunkPolicy,
    fallback:  ChunkPolicy,
    threshold: Length = Length(64 * 1024)
  ): ChunkPolicy = new ChunkPolicy:

    def pipeline: ZPipeline[Any, Throwable, Byte, Bytes] =
      // Try primary policy first, fall back if chunks are too large
      primary.pipeline.mapZIO { bytes =>
        if bytes.getSize > threshold then
          // Re-chunk using fallback policy
          ZStream
            .fromChunk(bytes)
            .via(fallback.pipeline)
            .runCollect
            .map(_.head) // Take first chunk from fallback
        else ZIO.succeed(bytes)
      }

  /**
   * Default policy for general document processing
   */
  val default: ChunkPolicy = rollingHash()

/**
 * PDF-aware chunker that respects stream boundaries
 */
private object PdfAwareChunker:
  def pipeline(maxChunkSize: Length): ZPipeline[Any, Throwable, Byte, Bytes] =
    ZPipeline
      .mapAccum[Byte, PdfState, Option[Chunk[Byte]]](PdfState.empty) { case (state, byte) =>
        val newState = state.update(byte)
        if newState.shouldEmit(maxChunkSize) then
          val (resetState, chunk) = newState.emitChunk
          (resetState, Some(chunk))
        else (newState, None)
      }
      .collect { case Some(chunk) => chunk }
      .mapZIO(chunk => ZIO.fromEither(Bytes.either(chunk)).mapError(Throwable(_)))

  case class PdfState(
    buffer:      Chunk[Byte] = Chunk.empty,
    inStream:    Boolean = false,
    streamDepth: Int = 0
  ):
    def update(byte: Byte): PdfState =
      val newBuffer = buffer :+ byte
      val content   = new String(newBuffer.takeRight(10).toArray)

      val newInStream =
        if content.contains("stream") then true
        else if content.contains("endstream") then false
        else inStream

      copy(buffer = newBuffer, inStream = newInStream)

    def shouldEmit(maxSize: Length): Boolean =
      !inStream && (buffer.size >= maxSize.toInt ||
        new String(buffer.takeRight(10).toArray).contains("endstream"))

    def emitChunk: (PdfState, Chunk[Byte]) =
      (copy(buffer = Chunk.empty), buffer)

  object PdfState:
    val empty: PdfState = PdfState()

/**
 * Token-aware chunker for structured data
 */
private object TokenAwareChunker:
  def pipeline(
    tokens:       Set[String],
    maxChunkSize: Length
  ): ZPipeline[Any, Throwable, Byte, Bytes] =
    ZPipeline
      .mapAccum[Byte, TokenState, Option[Chunk[Byte]]](TokenState.empty(tokens)) { case (state, byte) =>
        val newState = state.update(byte)
        if newState.shouldEmit(maxChunkSize) then
          val (resetState, chunk) = newState.emitChunk
          (resetState, Some(chunk))
        else (newState, None)
      }
      .collect { case Some(chunk) => chunk }
      .mapZIO(chunk => ZIO.fromEither(Bytes.either(chunk)).mapError(Throwable(_)))

  private[torrent] case class TokenState(
    buffer:      Chunk[Byte] = Chunk.empty,
    tokens:      Set[String],
    recentBytes: String = ""
  ):
    def update(byte: Byte): TokenState =
      val newBuffer = buffer :+ byte
      val newRecent = (recentBytes + byte.toChar).takeRight(20)
      copy(buffer = newBuffer, recentBytes = newRecent)

    def shouldEmit(maxSize: Length): Boolean =
      buffer.size >= maxSize.toInt ||
        tokens.exists(token => recentBytes.endsWith(token))

    def emitChunk: (TokenState, Chunk[Byte]) =
      (copy(buffer = Chunk.empty, recentBytes = ""), buffer)

  object TokenState:
    def empty(tokens: Set[String]): TokenState = TokenState(tokens = tokens)
