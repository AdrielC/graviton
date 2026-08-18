package graviton.core.scan

import kyo.Record
import kyo.Record.`~`
import zio.{Chunk, ChunkBuilder}
import zio.stream.{ZChannel, ZPipeline}

/**
 * Composable stateful stream transducer with zero-overhead composition.
 *
 * ==Design: Hot State vs Summary==
 *
 * Every transducer has two state representations:
 *   - `Hot`: the fast internal state used in the processing loop. Typically
 *     primitives, arrays, or tuples. '''Zero allocations per step.'''
 *   - `S` (Summary): the user-facing state, typically a `kyo.Record` with
 *     named fields. '''Only constructed when the user asks for it''' (via
 *     `runChunk`, `toSink`, `summarize`, or `flush`).
 *
 * When two transducers are composed via `>>>` or `&&&`, their `Hot` types
 * are combined as a tuple `(left.Hot, right.Hot)`. Their summary types are
 * merged via [[StateMerge]] (Record field union). The hot-path loop NEVER
 * constructs Records — only tuples of primitives. Records are materialized
 * once at the end when `toSummary` is called.
 *
 * This means `countBytes >>> hashBytes >>> rechunk` composed via `>>>`
 * runs at the '''same speed as hand-written imperative code'''.
 *
 * ==Composition==
 *   - `>>>` (sequential): pipe output of left into input of right
 *   - `&&&` (fanout): run both on same input, pair outputs
 *   - `.map` / `.filter` / `.contramap`: fuse adjacent transforms
 *
 * ==Compilation targets==
 *   - `runChunk(inputs)`: pure in-memory, returns `(S, Chunk[O])`
 *   - `toSink`: `ZSink` that yields `(S, Chunk[O])` as summary
 *   - `toPipeline`: `ZPipeline` (summary discarded)
 *   - `toChannel`: `ZChannel` that yields `S` as terminal value
 *   - `toTransducingSink`: `ZSink` for `stream.transduce` pattern
 *
 * @tparam I  Input element type (contravariant)
 * @tparam O  Output element type (covariant)
 * @tparam S  Summary type (user-facing, typically a Record)
 */
trait Transducer[-I, +O, S]:

  /** Fast internal state type. Primitives/tuples for zero-alloc hot path. */
  type Hot

  /** Create fresh hot state for a new run. */
  def initHot: Hot

  /** Process one input element. Returns updated hot state and outputs. */
  def step(h: Hot, i: I): (Hot, Chunk[O])

  /** End-of-stream. Returns final hot state and trailing outputs. */
  def flush(h: Hot): (Hot, Chunk[O])

  /** Project hot state to user-facing summary. Only called at boundaries. */
  def toSummary(h: Hot): S

  /** Process a whole chunk. Default loops over `step`; override for fused paths. */
  def stepChunk(h: Hot, chunk: Chunk[I]): (Hot, Chunk[O]) =
    if chunk.isEmpty then (h, Chunk.empty)
    else if chunk.length == 1 then step(h, chunk(0))
    else
      var state   = h
      val builder = ChunkBuilder.make[O]()
      var idx     = 0
      while idx < chunk.length do
        val (h2, out) = step(state, chunk(idx))
        state = h2
        out.foreach(o => builder += o)
        idx += 1
      (state, builder.result())

end Transducer

// =============================================================================
//  Companion: constructors, combinators, compilation
// =============================================================================

object Transducer:

  // ---------------------------------------------------------------------------
  //  Constructors
  // ---------------------------------------------------------------------------

  /** Identity: pass every element through unchanged. */
  def id[A]: Transducer[A, A, Unit] =
    new Transducer[A, A, Unit]:
      type Hot = Unit
      def initHot: Unit                            = ()
      def step(h: Unit, i: A): (Unit, Chunk[A])    = ((), Chunk.single(i))
      def flush(h: Unit): (Unit, Chunk[A])         = ((), Chunk.empty)
      def toSummary(h: Unit): Unit                 = ()
      override def stepChunk(h: Unit, c: Chunk[A]) = ((), c)

  /** Lift a pure function. Stateless, one-to-one. */
  def map[I, O](f: I => O): Transducer[I, O, Unit] =
    Mapped(id[I], f)

  /** Lift a predicate. Stateless filter. */
  def filter[A](p: A => Boolean): Transducer[A, A, Unit] =
    Filtered(id[A], p)

  /** Stateless one-to-many. */
  def flatMap[I, O](f: I => Chunk[O]): Transducer[I, O, Unit] =
    new Transducer[I, O, Unit]:
      type Hot = Unit
      def initHot: Unit                         = ()
      def step(h: Unit, i: I): (Unit, Chunk[O]) = ((), f(i))
      def flush(h: Unit): (Unit, Chunk[O])      = ((), Chunk.empty)
      def toSummary(h: Unit): Unit              = ()

  /** The most general stateful constructor. Hot state IS the summary. */
  def fold[I, O, S0](initial: => S0)(
    stepFn: (S0, I) => (S0, Chunk[O])
  )(
    flushFn: S0 => (S0, Chunk[O])
  ): Transducer[I, O, S0] =
    new Transducer[I, O, S0]:
      type Hot = S0
      def initHot: S0                       = initial
      def step(h: S0, i: I): (S0, Chunk[O]) = stepFn(h, i)
      def flush(h: S0): (S0, Chunk[O])      = flushFn(h)
      def toSummary(h: S0): S0              = h

  /** One-to-one stateful fold. */
  def fold1[I, O, S0](initial: => S0)(
    stepFn: (S0, I) => (S0, O)
  )(
    flushFn: S0 => (S0, Chunk[O])
  ): Transducer[I, O, S0] =
    new Transducer[I, O, S0]:
      type Hot = S0
      def initHot: S0                       = initial
      def step(h: S0, i: I): (S0, Chunk[O]) =
        val (h2, o) = stepFn(h, i)
        (h2, Chunk.single(o))
      def flush(h: S0): (S0, Chunk[O])      = flushFn(h)
      def toSummary(h: S0): S0              = h

  /** Accumulator: output IS the state after each step. */
  def accumulate[I, S0](initial: => S0)(f: (S0, I) => S0): Transducer[I, S0, S0] =
    fold1[I, S0, S0](initial) { (s, i) =>
      val n = f(s, i); (n, n)
    }(s => (s, Chunk.empty))

  // ---------------------------------------------------------------------------
  //  Internal fused wrappers
  // ---------------------------------------------------------------------------

  private final class Mapped[I, M, O, S](
    val base: Transducer[I, M, S],
    val f: M => O,
  ) extends Transducer[I, O, S]:
    type Hot = base.Hot
    def initHot: Hot                        = base.initHot
    def step(h: Hot, i: I): (Hot, Chunk[O]) =
      val (h2, ms) = base.step(h, i)
      (h2, ms.map(f))
    def flush(h: Hot): (Hot, Chunk[O])      =
      val (h2, ms) = base.flush(h)
      (h2, ms.map(f))
    def toSummary(h: Hot): S                = base.toSummary(h)

  private final class Filtered[I, O, S](
    val base: Transducer[I, O, S],
    val p: O => Boolean,
  ) extends Transducer[I, O, S]:
    type Hot = base.Hot
    def initHot: Hot                        = base.initHot
    def step(h: Hot, i: I): (Hot, Chunk[O]) =
      val (h2, os) = base.step(h, i)
      (h2, os.filter(p))
    def flush(h: Hot): (Hot, Chunk[O])      =
      val (h2, os) = base.flush(h)
      (h2, os.filter(p))
    def toSummary(h: Hot): S                = base.toSummary(h)

  private final class Contramapped[I, M, O, S](
    val base: Transducer[M, O, S],
    val g: I => M,
  ) extends Transducer[I, O, S]:
    type Hot = base.Hot
    def initHot: Hot                        = base.initHot
    def step(h: Hot, i: I): (Hot, Chunk[O]) = base.step(h, g(i))
    def flush(h: Hot): (Hot, Chunk[O])      = base.flush(h)
    def toSummary(h: Hot): S                = base.toSummary(h)

  // ---------------------------------------------------------------------------
  //  Extension methods
  // ---------------------------------------------------------------------------

  extension [I, O, S](self: Transducer[I, O, S])

    /** Post-transform outputs. Fuses adjacent maps. */
    def map[O2](f: O => O2): Transducer[I, O2, S] =
      self match
        case m: Mapped[I, m, O, S] @unchecked =>
          Mapped(m.base, m.f.andThen(f))
        case _                                =>
          Mapped(self, f)

    /** Pre-transform inputs. Fuses adjacent contramaps. */
    def contramap[I2](g: I2 => I): Transducer[I2, O, S] =
      self match
        case c: Contramapped[I2, i, O, S] @unchecked =>
          Contramapped(c.base, g.andThen(c.g))
        case _                                       =>
          Contramapped(self, g)

    def dimap[I2, O2](pre: I2 => I, post: O => O2): Transducer[I2, O2, S] =
      self.contramap(pre).map(post)

    /** Post-filter outputs. Fuses adjacent filters. */
    def filter(p: O => Boolean): Transducer[I, O, S] =
      self match
        case f: Filtered[I, O, S] @unchecked =>
          Filtered(f.base, o => f.p(o) && p(o))
        case _                               =>
          Filtered(self, p)

    // --- Sequential composition (>>>) ----------------------------------------

    def andThen[O2, S2, SOut](that: Transducer[O, O2, S2])(using sm: StateMerge.Aux[S, S2, SOut]): Transducer[I, O2, SOut] =
      new Transducer[I, O2, SOut]:
        type Hot = (self.Hot, that.Hot)

        def initHot: Hot = (self.initHot, that.initHot)

        def step(h: Hot, i: I): (Hot, Chunk[O2]) =
          val (h1Next, mids) = self.step(h._1, i)
          var h2             = h._2
          val builder        = ChunkBuilder.make[O2]()
          var idx            = 0
          while idx < mids.length do
            val (h2Next, os) = that.step(h2, mids(idx))
            h2 = h2Next
            os.foreach(o => builder += o)
            idx += 1
          ((h1Next, h2), builder.result())

        def flush(h: Hot): (Hot, Chunk[O2]) =
          val (h1Next, mids)  = self.flush(h._1)
          var h2              = h._2
          val builder         = ChunkBuilder.make[O2]()
          var idx             = 0
          while idx < mids.length do
            val (h2Next, os) = that.step(h2, mids(idx))
            h2 = h2Next
            os.foreach(o => builder += o)
            idx += 1
          val (h2Final, tail) = that.flush(h2)
          tail.foreach(o => builder += o)
          ((h1Next, h2Final), builder.result())

        def toSummary(h: Hot): SOut =
          sm.merge(self.toSummary(h._1), that.toSummary(h._2))

    def >>>[O2, S2, SOut](that: Transducer[O, O2, S2])(using StateMerge.Aux[S, S2, SOut]): Transducer[I, O2, SOut] =
      andThen(that)

    // --- Parallel / fanout (&&&) ---------------------------------------------

    def fanout[O2, S2, SOut](that: Transducer[I, O2, S2])(using sm: StateMerge.Aux[S, S2, SOut]): Transducer[I, (O, O2), SOut] =
      new Transducer[I, (O, O2), SOut]:
        type Hot = (self.Hot, that.Hot)

        def initHot: Hot = (self.initHot, that.initHot)

        def step(h: Hot, i: I): (Hot, Chunk[(O, O2)]) =
          val (h1, os1) = self.step(h._1, i)
          val (h2, os2) = that.step(h._2, i)
          val n         = math.min(os1.length, os2.length)
          val builder   = ChunkBuilder.make[(O, O2)]()
          var idx       = 0
          while idx < n do
            builder += ((os1(idx), os2(idx)))
            idx += 1
          ((h1, h2), builder.result())

        def flush(h: Hot): (Hot, Chunk[(O, O2)]) =
          val (h1, os1) = self.flush(h._1)
          val (h2, os2) = that.flush(h._2)
          val n         = math.min(os1.length, os2.length)
          val builder   = ChunkBuilder.make[(O, O2)]()
          var idx       = 0
          while idx < n do
            builder += ((os1(idx), os2(idx)))
            idx += 1
          ((h1, h2), builder.result())

        def toSummary(h: Hot): SOut =
          sm.merge(self.toSummary(h._1), that.toSummary(h._2))

    def &&&[O2, S2, SOut](that: Transducer[I, O2, S2])(using StateMerge.Aux[S, S2, SOut]): Transducer[I, (O, O2), SOut] =
      fanout(that)

    // --- Compilation / execution ---------------------------------------------

    /** Run on an in-memory collection. Returns `(summary, outputs)`. */
    def runChunk(inputs: Iterable[I]): (S, Chunk[O]) =
      var h          = self.initHot
      val builder    = ChunkBuilder.make[O]()
      inputs.foreach { i =>
        val (h2, os) = self.step(h, i)
        h = h2
        os.foreach(o => builder += o)
      }
      val (hf, tail) = self.flush(h)
      tail.foreach(o => builder += o)
      (self.toSummary(hf), builder.result())

    def run(inputs: Iterable[I]): Chunk[O] = runChunk(inputs)._2

    def summarize(inputs: Iterable[I]): S = runChunk(inputs)._1

    def toChannel: ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], S] =
      ZChannel.unwrap {
        zio.ZIO.succeed {
          var h: self.Hot = self.initHot

          def loop: ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], S] =
            ZChannel.readWith(
              (chunk: Chunk[I]) =>
                val (h2, out) = self.stepChunk(h, chunk)
                h = h2
                if out.isEmpty then loop
                else ZChannel.write(out) *> loop
              ,
              (_: Any) => ZChannel.succeedNow(self.toSummary(h)),
              (_: Any) =>
                val (hf, tail) = self.flush(h)
                h = hf
                val summary    = self.toSummary(hf)
                if tail.isEmpty then ZChannel.succeedNow(summary)
                else ZChannel.write(tail) *> ZChannel.succeedNow(summary),
            )

          loop
        }
      }

    def toPipeline: ZPipeline[Any, Nothing, I, O] =
      ZPipeline.fromChannel(self.toChannel.mapOut(identity).unit)

    def toSink: zio.stream.ZSink[Any, Nothing, I, Nothing, (S, Chunk[O])] =
      zio.stream.ZSink
        .foldLeftChunks[I, (self.Hot, ChunkBuilder[O])]((self.initHot, ChunkBuilder.make[O]())) { (acc, chunk) =>
          val (h, builder) = acc
          val (h2, out)    = self.stepChunk(h, chunk)
          out.foreach(o => builder += o)
          (h2, builder)
        }
        .map { case (h, builder) =>
          val (hf, tail) = self.flush(h)
          tail.foreach(o => builder += o)
          (self.toSummary(hf), builder.result())
        }

    def toTransducingSink: zio.stream.ZSink[Any, Nothing, I, I, S] =
      zio.stream.ZSink.fromChannel(
        ZChannel.suspend {
          var h: self.Hot = self.initHot

          def loop: ZChannel[Any, zio.ZNothing, Chunk[I], Any, Nothing, Chunk[I], S] =
            ZChannel.readWith(
              (chunk: Chunk[I]) =>
                val (h2, _) = self.stepChunk(h, chunk)
                h = h2
                loop
              ,
              (_: Any) => ZChannel.succeedNow(self.toSummary(h)),
              (_: Any) =>
                val (hf, _) = self.flush(h)
                h = hf
                ZChannel.succeedNow(self.toSummary(hf)),
            )

          loop
        }
      )

  // ---------------------------------------------------------------------------
  //  Batteries
  // ---------------------------------------------------------------------------

  def counter[A]: Transducer[A, Long, Record["count" ~ Long]] =
    type S = Record["count" ~ Long]
    new Transducer[A, Long, S]:
      type Hot = Long
      def initHot: Long                            = 0L
      def step(h: Long, i: A): (Long, Chunk[Long]) =
        val next = h + 1
        (next, Chunk.single(next))
      def flush(h: Long): (Long, Chunk[Long])      = (h, Chunk.empty)
      def toSummary(h: Long): S                    = (Record.empty & ("count" ~ h)).asInstanceOf[S]

  def byteCounter: Transducer[Chunk[Byte], Long, Record["totalBytes" ~ Long]] =
    type S = Record["totalBytes" ~ Long]
    new Transducer[Chunk[Byte], Long, S]:
      type Hot = Long
      def initHot: Long                                          = 0L
      def step(h: Long, bytes: Chunk[Byte]): (Long, Chunk[Long]) =
        val next = h + bytes.length
        (next, Chunk.single(next))
      def flush(h: Long): (Long, Chunk[Long])                    = (h, Chunk.empty)
      def toSummary(h: Long): S                                  = (Record.empty & ("totalBytes" ~ h)).asInstanceOf[S]

  def summer[A: kyo.Tag](using num: Numeric[A]): Transducer[A, A, Record["sum" ~ A]] =
    type S = Record["sum" ~ A]
    new Transducer[A, A, S]:
      type Hot = A
      def initHot: A                      = num.zero
      def step(h: A, a: A): (A, Chunk[A]) =
        val next = num.plus(h, a)
        (next, Chunk.single(next))
      def flush(h: A): (A, Chunk[A])      = (h, Chunk.empty)
      def toSummary(h: A): S              = (Record.empty & ("sum" ~ h)).asInstanceOf[S]

  def window[A](size: Int): Transducer[A, Vector[A], Vector[A]] =
    val n = math.max(1, size)
    fold1[A, Vector[A], Vector[A]](Vector.empty) { (s, a) =>
      val next = if s.length >= n then s.tail :+ a else s :+ a
      (next, next)
    }(s => (s, Chunk.empty))

end Transducer
