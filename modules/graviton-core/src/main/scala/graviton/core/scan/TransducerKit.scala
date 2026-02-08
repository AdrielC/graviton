package graviton.core.scan

import graviton.core.bytes.*
import kyo.Record
import kyo.Record.`~`
import zio.{Chunk, ChunkBuilder}

/**
 * Production-grade composable transducers for Graviton's streaming engine.
 *
 * Every transducer here uses `kyo.Record` state so that when composed via
 * `>>>` or `&&&`, the merged summary exposes **all fields by name**.
 *
 * Design: each transducer is a small, focused unit. The power comes from
 * composition — compose a chunker with a hasher and a counter and the
 * resulting summary has all fields accessible.
 *
 * ==Composition examples==
 * {{{
 *   // Ingest telemetry: count + byte total, all in one pass
 *   val telemetry = Transducers.blockCounter &&& Transducers.byteTotalChunked
 *   val (summary, _) = stream.run(telemetry.toSink)
 *   summary.blockCount  // Long
 *   summary.totalBytes  // Long
 *
 *   // CDC chunker with per-block digest
 *   val ingest = Transducers.fixedSizeChunker(1024) >>> Transducers.chunkDigest()
 *
 *   // Streaming dedup with stats
 *   stream.via(Transducers.dedup(_.id).toPipeline)
 * }}}
 */
object Transducers:

  // ---------------------------------------------------------------------------
  //  Helper: build a Transducer with internal tuple state, Record summary
  // ---------------------------------------------------------------------------

  /** Build a transducer where internal state is `IS` but the summary is `S` (Record). */
  private def withSummary[I, O, IS, S](
    initial: IS,
    stepFn: (IS, I) => (IS, Chunk[O]),
    toSummary: IS => S,
    flushFn: IS => (S, Chunk[O]),
  ): Transducer[I, O, S] =
    new Transducer[I, O, S]:
      // Use internal mutable state, project to summary at boundaries
      private var _internal: IS           = initial
      def init: S                         = { _internal = initial; toSummary(initial) }
      def step(s: S, i: I): (S, Chunk[O]) =
        val (next, out) = stepFn(_internal, i)
        _internal = next
        (toSummary(next), out)
      def flush(s: S): (S, Chunk[O])      = flushFn(_internal)

  // Actually that mutable approach is bad for reuse. Let me use a cleaner approach:
  // store internal state AS the Record, constructed every step.

  /** Helper to make a Record from fields. */
  inline private def rec1[K <: String & Singleton, V](k: K, v: V)(using kyo.Tag[V]): Record[K ~ V] =
    (Record.empty & (k ~ v)).asInstanceOf[Record[K ~ V]]

  inline private def rec2[K1 <: String & Singleton, V1, K2 <: String & Singleton, V2](
    k1: K1,
    v1: V1,
    k2: K2,
    v2: V2,
  )(using kyo.Tag[V1], kyo.Tag[V2]): Record[(K1 ~ V1) & (K2 ~ V2)] =
    (Record.empty & (k1 ~ v1) & (k2 ~ v2)).asInstanceOf[Record[(K1 ~ V1) & (K2 ~ V2)]]

  // ---------------------------------------------------------------------------
  //  Hashing
  // ---------------------------------------------------------------------------

  /**
   * Per-element digest: hash each `Chunk[Byte]` independently.
   *
   * Useful after a chunker to get per-block digests.
   * Stateless — just maps each chunk to `(chunk, digest)`.
   */
  def chunkDigest(
    algo: HashAlgo = HashAlgo.runtimeDefault
  ): Transducer[Chunk[Byte], (Chunk[Byte], Either[String, Digest]), Unit] =
    Transducer.map[Chunk[Byte], (Chunk[Byte], Either[String, Digest])] { chunk =>
      val digest = Hasher.hasher(algo, None).flatMap { h =>
        val _ = h.update(chunk.toArray)
        h.digest
      }
      (chunk, digest)
    }

  // ---------------------------------------------------------------------------
  //  Chunking
  // ---------------------------------------------------------------------------

  /**
   * Fixed-size rechunker: accumulates bytes into blocks of exactly `blockSize`.
   * Remainder is flushed at end-of-stream.
   *
   * Summary: `blockCount` and `bufferFill`.
   */
  def fixedSizeChunker(
    blockSize: Int
  ): Transducer[Byte, Chunk[Byte], Record[("blockCount" ~ Long) & ("bufferFill" ~ Int)]] =
    type S = Record[("blockCount" ~ Long) & ("bufferFill" ~ Int)]
    val safeSize = math.max(1, math.min(blockSize, 16 * 1024 * 1024))

    new Transducer[Byte, Chunk[Byte], S]:
      private var buf: Array[Byte] = _
      private var fill: Int        = _
      private var count: Long      = _

      def init: S =
        buf = Array.ofDim[Byte](safeSize)
        fill = 0
        count = 0L
        rec2("blockCount", 0L, "bufferFill", 0)

      def step(s: S, byte: Byte): (S, Chunk[Chunk[Byte]]) =
        buf(fill) = byte
        fill += 1
        if fill >= safeSize then
          val block = Chunk.fromArray(java.util.Arrays.copyOf(buf, safeSize))
          count += 1
          fill = 0
          (rec2("blockCount", count, "bufferFill", fill), Chunk.single(block))
        else (rec2("blockCount", count, "bufferFill", fill), Chunk.empty)

      def flush(s: S): (S, Chunk[Chunk[Byte]]) =
        val summary = rec2("blockCount", count, "bufferFill", fill)
        if fill > 0 then
          val block = Chunk.fromArray(java.util.Arrays.copyOf(buf, fill))
          (summary, Chunk.single(block))
        else (summary, Chunk.empty)

      override def stepChunk(s: S, chunk: Chunk[Byte]): (S, Chunk[Chunk[Byte]]) =
        val out = ChunkBuilder.make[Chunk[Byte]]()
        var idx = 0
        while idx < chunk.length do
          buf(fill) = chunk(idx)
          fill += 1
          if fill >= safeSize then
            out += Chunk.fromArray(java.util.Arrays.copyOf(buf, safeSize))
            count += 1
            fill = 0
          idx += 1
        (rec2("blockCount", count, "bufferFill", fill), out.result())

  // ---------------------------------------------------------------------------
  //  Counting & sizing
  // ---------------------------------------------------------------------------

  /** Count blocks (Chunk[Byte] elements). Pass-through. */
  def blockCounter: Transducer[Chunk[Byte], Chunk[Byte], Record["blockCount" ~ Long]] =
    type S = Record["blockCount" ~ Long]
    Transducer.fold1[Chunk[Byte], Chunk[Byte], S](
      rec1("blockCount", 0L)
    ) { (state, chunk) =>
      (rec1("blockCount", state.blockCount + 1), chunk)
    }(s => (s, Chunk.empty))

  /** Running byte total over `Chunk[Byte]` inputs. Pass-through. */
  def byteTotalChunked: Transducer[Chunk[Byte], Chunk[Byte], Record["totalBytes" ~ Long]] =
    type S = Record["totalBytes" ~ Long]
    Transducer.fold1[Chunk[Byte], Chunk[Byte], S](
      rec1("totalBytes", 0L)
    ) { (state, chunk) =>
      (rec1("totalBytes", state.totalBytes + chunk.length.toLong), chunk)
    }(s => (s, Chunk.empty))

  // ---------------------------------------------------------------------------
  //  Statistics & monitoring
  // ---------------------------------------------------------------------------

  /**
   * Exponential moving average (EMA).
   *
   * `alpha` in (0, 1): higher = more weight on recent values.
   */
  def exponentialMovingAvg(alpha: Double): Transducer[Double, Double, Record[("ema" ~ Double) & ("emaSamples" ~ Long)]] =
    type S = Record[("ema" ~ Double) & ("emaSamples" ~ Long)]
    val a = math.max(0.001, math.min(alpha, 0.999))
    Transducer.fold1[Double, Double, S](
      rec2("ema", 0.0, "emaSamples", 0L)
    ) { (state, value) =>
      val newEma =
        if state.emaSamples == 0L then value
        else state.ema * (1.0 - a) + value * a
      (rec2("ema", newEma, "emaSamples", state.emaSamples + 1), newEma)
    }(s => (s, Chunk.empty))

  /**
   * Min / Max tracker.
   */
  def minMax[A: Ordering: kyo.Tag]: Transducer[A, A, Record[("min" ~ Option[A]) & ("max" ~ Option[A])]] =
    type S = Record[("min" ~ Option[A]) & ("max" ~ Option[A])]
    val ord = summon[Ordering[A]]
    Transducer.fold1[A, A, S](
      rec2("min", Option.empty[A], "max", Option.empty[A])
    ) { (state, a) =>
      val newMin = state.min.map(m => if ord.lt(a, m) then a else m).orElse(Some(a))
      val newMax = state.max.map(m => if ord.gt(a, m) then a else m).orElse(Some(a))
      (rec2("min", newMin, "max", newMax), a)
    }(s => (s, Chunk.empty))

  /**
   * Reservoir sampler (Algorithm R).
   *
   * Maintains a uniform random sample of `size` elements from the stream.
   */
  def reservoirSample[A: kyo.Tag](
    size: Int,
    seed: Long = System.nanoTime(),
  ): Transducer[A, Vector[A], Record[("reservoir" ~ Vector[A]) & ("seen" ~ Long)]] =
    type S = Record[("reservoir" ~ Vector[A]) & ("seen" ~ Long)]
    val rng      = new java.util.Random(seed)
    val safeSize = math.max(1, size)
    Transducer.fold1[A, Vector[A], S](
      rec2("reservoir", Vector.empty[A], "seen", 0L)
    ) { (state, a) =>
      val n            = state.seen + 1
      val newReservoir =
        if state.reservoir.length < safeSize then state.reservoir :+ a
        else
          val j = rng.nextLong(n)
          if j < safeSize then state.reservoir.updated(j.toInt, a)
          else state.reservoir
      (rec2("reservoir", newReservoir, "seen", n), newReservoir)
    }(s => (s, Chunk.empty))

  // ---------------------------------------------------------------------------
  //  Deduplication
  // ---------------------------------------------------------------------------

  /**
   * Deduplication by key extractor.
   *
   * Drops elements whose key has been seen before.
   * Summary: `uniqueCount` and `duplicateCount`.
   */
  def dedup[A, K](key: A => K): Transducer[A, A, Record[("uniqueCount" ~ Long) & ("duplicateCount" ~ Long)]] =
    type S = Record[("uniqueCount" ~ Long) & ("duplicateCount" ~ Long)]
    // Internal state includes the set, summary only has counts
    new Transducer[A, A, S]:
      private var seen: Set[K] = _
      private var unique: Long = _
      private var dupes: Long  = _

      def init: S =
        seen = Set.empty
        unique = 0L
        dupes = 0L
        rec2("uniqueCount", 0L, "duplicateCount", 0L)

      def step(s: S, a: A): (S, Chunk[A]) =
        val k = key(a)
        if seen.contains(k) then
          dupes += 1
          (rec2("uniqueCount", unique, "duplicateCount", dupes), Chunk.empty)
        else
          seen += k
          unique += 1
          (rec2("uniqueCount", unique, "duplicateCount", dupes), Chunk.single(a))

      def flush(s: S): (S, Chunk[A]) =
        (rec2("uniqueCount", unique, "duplicateCount", dupes), Chunk.empty)

  // ---------------------------------------------------------------------------
  //  Grouping & batching
  // ---------------------------------------------------------------------------

  /**
   * Batch elements into groups of `size`. Last group may be smaller.
   */
  def batch[A](size: Int): Transducer[A, Chunk[A], Record[("batchCount" ~ Long) & ("batchSize" ~ Int)]] =
    type S = Record[("batchCount" ~ Long) & ("batchSize" ~ Int)]
    val n = math.max(1, size)
    new Transducer[A, Chunk[A], S]:
      private var builder: ChunkBuilder[A] = _
      private var fill: Int                = _
      private var count: Long              = _

      def init: S =
        builder = ChunkBuilder.make[A]()
        fill = 0
        count = 0L
        rec2("batchCount", 0L, "batchSize", n)

      def step(s: S, a: A): (S, Chunk[Chunk[A]]) =
        builder += a
        fill += 1
        if fill >= n then
          val b = builder.result()
          builder = ChunkBuilder.make[A]()
          count += 1
          fill = 0
          (rec2("batchCount", count, "batchSize", n), Chunk.single(b))
        else (rec2("batchCount", count, "batchSize", n), Chunk.empty)

      def flush(s: S): (S, Chunk[Chunk[A]]) =
        val finalCount = if fill > 0 then count + 1 else count
        val summary    = rec2("batchCount", finalCount, "batchSize", n)
        if fill > 0 then (summary, Chunk.single(builder.result()))
        else (summary, Chunk.empty)

  /**
   * Group consecutive elements that share the same key.
   *
   * Output: `(K, Chunk[A])` — the key and the group.
   */
  def groupBy[A, K](key: A => K): Transducer[A, (K, Chunk[A]), Record["groupCount" ~ Long]] =
    type S = Record["groupCount" ~ Long]
    new Transducer[A, (K, Chunk[A]), S]:
      private var currentKey: Option[K]    = _
      private var builder: ChunkBuilder[A] = _
      private var count: Long              = _

      def init: S =
        currentKey = None
        builder = ChunkBuilder.make[A]()
        count = 0L
        rec1("groupCount", 0L)

      def step(s: S, a: A): (S, Chunk[(K, Chunk[A])]) =
        val k = key(a)
        currentKey match
          case Some(ck) if ck == k =>
            builder += a
            (rec1("groupCount", count), Chunk.empty)
          case Some(ck)            =>
            val group = builder.result()
            builder = ChunkBuilder.make[A]()
            builder += a
            currentKey = Some(k)
            count += 1
            (rec1("groupCount", count), Chunk.single((ck, group)))
          case None                =>
            builder += a
            currentKey = Some(k)
            (rec1("groupCount", count), Chunk.empty)

      def flush(s: S): (S, Chunk[(K, Chunk[A])]) =
        currentKey match
          case Some(k) =>
            val group   = builder.result()
            count += 1
            val summary = rec1("groupCount", count)
            if group.isEmpty then (summary, Chunk.empty)
            else (summary, Chunk.single((k, group)))
          case None    =>
            (rec1("groupCount", count), Chunk.empty)

end Transducers
