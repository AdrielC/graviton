package graviton.core.scan

import graviton.core.bytes.*
import kyo.Record
import kyo.Record.`~`
import zio.{Chunk, ChunkBuilder}

/**
 * Production-grade composable transducers for Graviton's streaming engine.
 *
 * All transducers use the `Hot` state pattern: primitives in the loop,
 * Record summaries only at boundaries.
 */
object Transducers:

  inline private def rec1[K <: String & Singleton, V](k: K, v: V)(using kyo.Tag[V]): Record[K ~ V] =
    (Record.empty & (k ~ v)).asInstanceOf[Record[K ~ V]]

  inline private def rec2[K1 <: String & Singleton, V1, K2 <: String & Singleton, V2](
    k1: K1,
    v1: V1,
    k2: K2,
    v2: V2,
  )(using kyo.Tag[V1], kyo.Tag[V2]): Record[(K1 ~ V1) & (K2 ~ V2)] =
    (Record.empty & (k1 ~ v1) & (k2 ~ v2)).asInstanceOf[Record[(K1 ~ V1) & (K2 ~ V2)]]

  // --- Hashing ---------------------------------------------------------------

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

  // --- Counting --------------------------------------------------------------

  def blockCounter: Transducer[Chunk[Byte], Chunk[Byte], Record["blockCount" ~ Long]] =
    type S = Record["blockCount" ~ Long]
    new Transducer[Chunk[Byte], Chunk[Byte], S]:
      type Hot = Long
      def initHot: Long                                             = 0L
      def step(h: Long, c: Chunk[Byte]): (Long, Chunk[Chunk[Byte]]) = (h + 1, Chunk.single(c))
      def flush(h: Long): (Long, Chunk[Chunk[Byte]])                = (h, Chunk.empty)
      def toSummary(h: Long): S                                     = rec1("blockCount", h)

  def byteTotalChunked: Transducer[Chunk[Byte], Chunk[Byte], Record["totalBytes" ~ Long]] =
    type S = Record["totalBytes" ~ Long]
    new Transducer[Chunk[Byte], Chunk[Byte], S]:
      type Hot = Long
      def initHot: Long                                             = 0L
      def step(h: Long, c: Chunk[Byte]): (Long, Chunk[Chunk[Byte]]) = (h + c.length.toLong, Chunk.single(c))
      def flush(h: Long): (Long, Chunk[Chunk[Byte]])                = (h, Chunk.empty)
      def toSummary(h: Long): S                                     = rec1("totalBytes", h)

  // --- Statistics ------------------------------------------------------------

  def exponentialMovingAvg(alpha: Double): Transducer[Double, Double, Record[("ema" ~ Double) & ("emaSamples" ~ Long)]] =
    type S = Record[("ema" ~ Double) & ("emaSamples" ~ Long)]
    val a = math.max(0.001, math.min(alpha, 0.999))
    new Transducer[Double, Double, S]:
      type Hot = (Double, Long) // ema, samples
      def initHot: Hot                                  = (0.0, 0L)
      def step(h: Hot, v: Double): (Hot, Chunk[Double]) =
        val newEma = if h._2 == 0L then v else h._1 * (1.0 - a) + v * a
        ((newEma, h._2 + 1), Chunk.single(newEma))
      def flush(h: Hot): (Hot, Chunk[Double])           = (h, Chunk.empty)
      def toSummary(h: Hot): S                          = rec2("ema", h._1, "emaSamples", h._2)

  def minMax[A: Ordering: kyo.Tag]: Transducer[A, A, Record[("min" ~ Option[A]) & ("max" ~ Option[A])]] =
    type S = Record[("min" ~ Option[A]) & ("max" ~ Option[A])]
    val ord = summon[Ordering[A]]
    new Transducer[A, A, S]:
      type Hot = (Option[A], Option[A])
      def initHot: Hot                        = (None, None)
      def step(h: Hot, a: A): (Hot, Chunk[A]) =
        val mn = h._1.map(m => if ord.lt(a, m) then a else m).orElse(Some(a))
        val mx = h._2.map(m => if ord.gt(a, m) then a else m).orElse(Some(a))
        ((mn, mx), Chunk.single(a))
      def flush(h: Hot): (Hot, Chunk[A])      = (h, Chunk.empty)
      def toSummary(h: Hot): S                = rec2("min", h._1, "max", h._2)

  def reservoirSample[A: kyo.Tag](
    size: Int,
    seed: Long = System.nanoTime(),
  ): Transducer[A, Vector[A], Record[("reservoir" ~ Vector[A]) & ("seen" ~ Long)]] =
    type S = Record[("reservoir" ~ Vector[A]) & ("seen" ~ Long)]
    val rng      = new java.util.Random(seed)
    val safeSize = math.max(1, size)
    new Transducer[A, Vector[A], S]:
      type Hot = (Vector[A], Long)
      def initHot: Hot                                = (Vector.empty, 0L)
      def step(h: Hot, a: A): (Hot, Chunk[Vector[A]]) =
        val n   = h._2 + 1
        val res =
          if h._1.length < safeSize then h._1 :+ a
          else { val j = rng.nextLong(n); if j < safeSize then h._1.updated(j.toInt, a) else h._1 }
        ((res, n), Chunk.single(res))
      def flush(h: Hot): (Hot, Chunk[Vector[A]])      = (h, Chunk.empty)
      def toSummary(h: Hot): S                        = rec2("reservoir", h._1, "seen", h._2)

  // --- Dedup -----------------------------------------------------------------

  def dedup[A, K](key: A => K): Transducer[A, A, Record[("uniqueCount" ~ Long) & ("duplicateCount" ~ Long)]] =
    type S = Record[("uniqueCount" ~ Long) & ("duplicateCount" ~ Long)]
    new Transducer[A, A, S]:
      type Hot = (Set[K], Long, Long) // seen, unique, dupes
      def initHot: Hot                        = (Set.empty, 0L, 0L)
      def step(h: Hot, a: A): (Hot, Chunk[A]) =
        val k = key(a)
        if h._1.contains(k) then ((h._1, h._2, h._3 + 1), Chunk.empty)
        else ((h._1 + k, h._2 + 1, h._3), Chunk.single(a))
      def flush(h: Hot): (Hot, Chunk[A])      = (h, Chunk.empty)
      def toSummary(h: Hot): S                = rec2("uniqueCount", h._2, "duplicateCount", h._3)

  // --- Batching --------------------------------------------------------------

  def batch[A](size: Int): Transducer[A, Chunk[A], Record[("batchCount" ~ Long) & ("batchSize" ~ Int)]] =
    type S = Record[("batchCount" ~ Long) & ("batchSize" ~ Int)]
    val n = math.max(1, size)
    new Transducer[A, Chunk[A], S]:
      type Hot = (ChunkBuilder[A], Int, Long) // builder, fill, count
      def initHot: Hot                               = (ChunkBuilder.make[A](), 0, 0L)
      def step(h: Hot, a: A): (Hot, Chunk[Chunk[A]]) =
        h._1 += a
        val nextFill = h._2 + 1
        if nextFill >= n then
          val b = h._1.result()
          ((ChunkBuilder.make[A](), 0, h._3 + 1), Chunk.single(b))
        else ((h._1, nextFill, h._3), Chunk.empty)
      def flush(h: Hot): (Hot, Chunk[Chunk[A]])      =
        if h._2 > 0 then ((h._1, 0, h._3 + 1), Chunk.single(h._1.result()))
        else (h, Chunk.empty)
      def toSummary(h: Hot): S                       = rec2("batchCount", h._3, "batchSize", n)

  def groupBy[A, K](key: A => K): Transducer[A, (K, Chunk[A]), Record["groupCount" ~ Long]] =
    type S = Record["groupCount" ~ Long]
    new Transducer[A, (K, Chunk[A]), S]:
      type Hot = (Option[K], ChunkBuilder[A], Long)
      def initHot: Hot                                    = (None, ChunkBuilder.make[A](), 0L)
      def step(h: Hot, a: A): (Hot, Chunk[(K, Chunk[A])]) =
        val k = key(a)
        h._1 match
          case Some(ck) if ck == k =>
            h._2 += a
            (h, Chunk.empty)
          case Some(ck)            =>
            val group = h._2.result()
            val nb    = ChunkBuilder.make[A]()
            nb += a
            ((Some(k), nb, h._3 + 1), Chunk.single((ck, group)))
          case None                =>
            h._2 += a
            ((Some(k), h._2, h._3), Chunk.empty)
      def flush(h: Hot): (Hot, Chunk[(K, Chunk[A])])      =
        h._1 match
          case Some(k) =>
            val group = h._2.result()
            if group.isEmpty then ((h._1, h._2, h._3), Chunk.empty)
            else ((h._1, h._2, h._3 + 1), Chunk.single((k, group)))
          case None    => (h, Chunk.empty)
      def toSummary(h: Hot): S                            = rec1("groupCount", h._3)

end Transducers
