package graviton.chunking

import graviton.core.model.Block
import graviton.core.model.BlockBuilder
import graviton.core.model.Limits
import zio.*
import zio.stream.*
import zio.ChunkBuilder
import io.github.iltotore.iron.:|


import scala.collection.mutable
import io.github.iltotore.iron.constraint.numeric.{Greater, GreaterEqual}

/**
 * Anchored content-defined chunking built on top of an Aho-Corasick tokenizer
 * combined with a cost model powered by `ZSink.foldWeightedDecompose`.
 */
object AnchoredCdcPipeline:

  /** Describes a reusable set of anchor tokens. */
  final case class TokenPack(name: String, tokens: Chunk[Chunk[Byte]]):
    require(tokens.nonEmpty, "token pack must provide at least one token")
    require(tokens.forall(_.nonEmpty), "tokens must not be empty")

  object TokenPack:
    def fromStrings(name: String, tokens: Iterable[String], charset: String = "UTF-8"): TokenPack =
      val converted = tokens.iterator.map(_.getBytes(charset)).map(Chunk.fromArray).toList
      TokenPack(name, Chunk.fromIterable(converted))

  private final case class Segment(bytes: Chunk[Byte], anchored: Boolean):
    def nonEmpty: Boolean = bytes.nonEmpty

    def weight(anchorBonus: Int :| GreaterEqual[0]): Long =
      val base   = bytes.length.toLong.max(1L)
      val adjust = if anchored then anchorBonus.toLong else 0L
      (base - adjust).max(1L)

    def decompose(maxSize: Int): Chunk[Segment] =
      val cap = math.min(maxSize, Limits.MAX_BLOCK_SIZE_IN_BYTES)
      if bytes.length <= cap then Chunk.single(this)
      else
        val groups = bytes.toArray.grouped(cap).toArray
        Chunk.fromArray(groups.zipWithIndex.map { case (arr, idx) =>
          val chunk   = Chunk.fromArray(arr)
          val isFinal = idx == groups.length - 1
          Segment(chunk, anchored && isFinal)
        })

  private final case class Accumulator(buffer: Chunk[Byte], anchoredTail: Boolean):
    def append(segment: Segment): Accumulator =
      val combined = if buffer.isEmpty then segment.bytes else buffer ++ segment.bytes
      Accumulator(combined, segment.anchored)

    def nonEmpty: Boolean = buffer.nonEmpty

  private object Accumulator:
    val empty: Accumulator = Accumulator(Chunk.empty, anchoredTail = false)

  private final case class Automaton(nodes: Vector[Automaton.Node]):
    def step(state: Int, byte: Byte): (Int, Boolean) =
      var current = state
      val b       = byte & 0xff
      while current != 0 && !nodes(current).transitions.contains(b) do current = nodes(current).fail
      val next    = nodes(current).transitions.getOrElse(b, 0)
      val hit     = nodes(next).output
      (next, hit)

  private object Automaton:
    final case class Node(
      transitions: mutable.Map[Int, Int],
      var fail: Int,
      var output: Boolean,
    )

    def build(tokens: Chunk[Chunk[Byte]]): Automaton =
      val nodes = mutable.ArrayBuffer.empty[Node]
      nodes += Node(mutable.Map.empty, fail = 0, output = false)

      tokens.foreach { token =>
        var current = 0
        token.foreach { byte =>
          val key  = byte & 0xff
          val next = nodes(current).transitions.getOrElseUpdate(
            key, {
              nodes += Node(mutable.Map.empty, fail = 0, output = false)
              nodes.size - 1
            },
          )
          current = next
        }
        nodes(current).output = true
      }

      val queue = mutable.Queue.empty[Int]
      nodes(0).transitions.foreach { case (_, next) =>
        nodes(next).fail = 0
        queue.enqueue(next)
      }

      while queue.nonEmpty do
        val state = queue.dequeue()
        nodes(state).transitions.foreach { case (byte, next) =>
          queue.enqueue(next)
          var fail     = nodes(state).fail
          while fail != 0 && !nodes(fail).transitions.contains(byte) do fail = nodes(fail).fail
          val fallback = nodes(fail).transitions.getOrElse(byte, 0)
          nodes(next).fail = fallback
          nodes(next).output ||= nodes(fallback).output
        }

      Automaton(nodes.toVector)

  private final case class Tokenizer(state: Int, automaton: Automaton):
    def process(chunk: Chunk[Byte]): (Tokenizer, Chunk[Segment]) =
      if chunk.isEmpty then (this, Chunk.empty)
      else
        val builder = ChunkBuilder.make[Segment]()
        var index   = 0
        var start   = 0
        var current = state
        while index < chunk.length do
          val byte        = chunk(index)
          val (next, hit) = automaton.step(current, byte)
          val nextIndex   = index + 1
          if hit then
            val segment = Segment(chunk.slice(start, nextIndex), anchored = true)
            if segment.nonEmpty then builder += segment
            start = nextIndex
          current = next
          index = nextIndex
        if start < chunk.length then
          val segment = Segment(chunk.drop(start), anchored = false)
          if segment.nonEmpty then builder += segment
        (Tokenizer(current, automaton), builder.result())

  private final case class PipelineState(tokenizer: Tokenizer, pending: Chunk[Segment])
  private object PipelineState:
    def initial(pack: TokenPack): PipelineState =
      val automaton = Automaton.build(pack.tokens)
      PipelineState(Tokenizer(0, automaton), Chunk.empty)

  extension (pipeline: ZPipeline.type)
    def anchoredCdc(
      tokenPack: TokenPack,
      avgSize: Int :| Greater[0],
      anchorBonus: Int :| GreaterEqual[0],
    ): ZPipeline[Any, Throwable, Byte, Block] =

      val sink = ZSink.foldWeightedDecompose[Segment, Accumulator](Accumulator.empty)(
        (acc, segment) =>
          val base    = segment.weight(anchorBonus)
          val penalty = if acc.nonEmpty && acc.anchoredTail && !segment.anchored then avgSize.toLong else 0L
          base + penalty
        ,
        avgSize.toLong,
        segment => segment.decompose(avgSize),
      )((acc, segment) => acc.append(segment))

      def segmentsToBlocks(segments: Chunk[Segment]): Chunk[Block] =
        segments.flatMap(seg => BlockBuilder.chunkify(seg.bytes))

      ZPipeline.fromChannel {
        def emitSegments(segments: Chunk[Segment]): ZIO[Any, Throwable, (Chunk[Block], Chunk[Segment])] =
          if segments.isEmpty then ZIO.succeed(Chunk.empty -> Chunk.empty)
          else
            def loop(rem: Chunk[Segment], acc: Chunk[Block]): ZIO[Any, Throwable, (Chunk[Block], Chunk[Segment])] =
              if rem.isEmpty then ZIO.succeed(acc -> Chunk.empty)
              else
                ZStream
                  .fromChunk(rem)
                  .run(sink.collectLeftover)
                  .flatMap { case (accum, leftover) =>
                    if !accum.nonEmpty then ZIO.succeed(acc -> rem)
                    else
                      val blocks = BlockBuilder.chunkify(accum.buffer)
                      loop(leftover, acc ++ blocks)
                  }
            loop(segments, Chunk.empty)

        def loop(state: PipelineState): ZChannel[Any, Throwable, Chunk[Byte], Any, Throwable, Chunk[Block], Any] =
          ZChannel.readWith(
            (in: Chunk[Byte]) =>
              val (nextTokenizer, produced) = state.tokenizer.process(in)
              val combined                  =
                if state.pending.isEmpty then produced else state.pending ++ produced
              ZChannel
                .fromZIO(emitSegments(combined))
                .flatMap { case (blocks, leftover) =>
                  if blocks.isEmpty then loop(PipelineState(nextTokenizer, leftover))
                  else ZChannel.write(blocks) *> loop(PipelineState(nextTokenizer, leftover))
                }
            ,
            (err: Throwable) => ZChannel.fail(err),
            (_: Any) =>
              ZChannel
                .fromZIO(emitSegments(state.pending))
                .flatMap { case (blocks, leftover) =>
                  val leftoverBlocks = segmentsToBlocks(leftover)
                  val all            = if leftoverBlocks.isEmpty then blocks else blocks ++ leftoverBlocks
                  if all.isEmpty then ZChannel.unit else ZChannel.write(all)
                },
          )

        loop(PipelineState.initial(tokenPack))
      }
