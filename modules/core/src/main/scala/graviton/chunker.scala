package graviton

import zio.*
import zio.stream.*

trait Chunker:
  /** Split incoming bytes into blocks. */
  def chunk(blockSize: Int): ZSink[Any, Throwable, Byte, Nothing, Chunk[Chunk[Byte]]]

object Chunker:

  /** Fixed-size chunking; last chunk may be smaller. */
  val fixed: Chunker = new Chunker:
    def chunk(blockSize: Int): ZSink[Any, Throwable, Byte, Nothing, Chunk[Chunk[Byte]]] =
      ZSink.collectAll[Byte].map { data =>
        val chunk = Chunk.fromIterable(data)
        Chunk.fromIterable(chunk.grouped(blockSize).map(Chunk.fromIterable).toList)
      }
