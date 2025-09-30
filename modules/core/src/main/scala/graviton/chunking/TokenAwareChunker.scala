package graviton.chunking

import graviton.core.model.Block
import zio.*
import zio.stream.*

object TokenAwareChunker:

  def pipeline(tokens: Set[String], maxChunkSize: Int): ZPipeline[Any, Throwable, Byte, Block] =
    ZPipeline
      .fromChannel {
        def loop(state: State): ZChannel[Any, Throwable, Chunk[
          Byte
      ], Any, Throwable, Chunk[Chunk[Byte]], Any] =
          ZChannel.readWith(
            (in: Chunk[Byte]) =>
              val (next, out) = process(state, in, tokens, maxChunkSize)
              ZChannel.write(out) *> loop(next)
            ,
            (err: Throwable) => ZChannel.fail(err),
            (_: Any) => if state.buffer.isEmpty then ZChannel.unit else ZChannel.write(Chunk.single(state.buffer)),
          )
        loop(State.empty)
      }
      .mapChunksZIO { chunked =>
        ZIO.foreach(chunked)(bytes =>
          ZIO.fromEither(Block.fromChunk(bytes)).mapError(err => new IllegalArgumentException(err))
        )
      }

  private final case class State(buffer: Chunk[Byte], recent: String)
  private object State:
    val empty: State = State(Chunk.empty, "")

  private def process(
    init: State,
    in: Chunk[Byte],
    tokens: Set[String],
    maxSize: Int,
  ): (State, Chunk[Chunk[Byte]]) =
    var s   = init
    val out = scala.collection.mutable.ListBuffer.empty[Chunk[Byte]]
    var i   = 0
    while i < in.length do
      val b      = in(i)
      val buf    = s.buffer :+ b
      val rec    = (s.recent + b.toChar).takeRight(32)
      val should = buf.size >= maxSize || tokens.exists(t => rec.endsWith(t))
      if should then
        out += buf
        s = State.empty
      else s = State(buf, rec)
      i += 1
    (s, Chunk.fromIterable(out))
