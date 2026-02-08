package graviton.core.scan

import kyo.Record
import kyo.Record.`~`
import zio.{Chunk, ChunkBuilder}
import zio.stream.{ZChannel, ZPipeline}

/**
 * Composable stateful stream transducer with:
 *   - State composition via [[StateMerge]] (Aux pattern, dependent types)
 *   - Record-aware state union: `Record[A] ⊕ Record[B] = Record[A & B]`
 *   - `Unit` state as identity (zero overhead for stateless transforms)
 *   - Eager map/filter fusion (adjacent transforms collapse at construction)
 *   - Chunk-level step for one-to-many transforms (filter, flatMap)
 *   - Compiles to `ZPipeline` / `ZChannel` for ZIO integration
 *
 * The `step` function is one-element-in, zero-or-more-out (`Chunk[O]`).
 * This generalises one-to-one scans (use `Chunk.single`) and filters
 * (use `Chunk.empty`). For hot paths, override `stepChunk` to process
 * an entire `Chunk[I]` in one call.
 *
 * @tparam I  Input element type (contravariant)
 * @tparam O  Output element type (covariant)
 * @tparam S  Internal state type
 */
trait Transducer[-I, +O, S]:

  /** Fresh initial state for a new run. */
  def init: S

  /** Process one input element, returning updated state and zero-or-more outputs. */
  def step(s: S, i: I): (S, Chunk[O])

  /** End-of-stream finalization. May emit trailing outputs. */
  def flush(s: S): (S, Chunk[O])

  /**
   * Process a whole input chunk. Default loops over `step`; override for
   * fused hot-path implementations (e.g. chunkers, hashers).
   */
  def stepChunk(s: S, chunk: Chunk[I]): (S, Chunk[O]) =
    if chunk.isEmpty then (s, Chunk.empty)
    else if chunk.length == 1 then step(s, chunk(0))
    else
      var state   = s
      val builder = ChunkBuilder.make[O]()
      var idx     = 0
      while idx < chunk.length do
        val (s2, out) = step(state, chunk(idx))
        state = s2
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

  /** Identity: pass every element through unchanged. Stateless. */
  def id[A]: Transducer[A, A, Unit] =
    new Transducer[A, A, Unit]:
      def init: Unit                               = ()
      def step(s: Unit, i: A): (Unit, Chunk[A])    = ((), Chunk.single(i))
      def flush(s: Unit): (Unit, Chunk[A])         = ((), Chunk.empty)
      override def stepChunk(s: Unit, c: Chunk[A]) = ((), c) // fast path

  /** Lift a pure function. Stateless, one-to-one, fuses with adjacent maps. */
  def map[I, O](f: I => O): Transducer[I, O, Unit] =
    Mapped(id[I], f)

  /** Lift a predicate. Stateless, one-to-zero-or-one. */
  def filter[A](p: A => Boolean): Transducer[A, A, Unit] =
    Filtered(id[A], p)

  /** Stateless one-to-many. */
  def flatMap[I, O](f: I => Chunk[O]): Transducer[I, O, Unit] =
    new Transducer[I, O, Unit]:
      def init: Unit                            = ()
      def step(s: Unit, i: I): (Unit, Chunk[O]) = ((), f(i))
      def flush(s: Unit): (Unit, Chunk[O])      = ((), Chunk.empty)

  /** Stateful fold: the most general constructor. */
  def fold[I, O, S0](initial: => S0)(
    stepFn: (S0, I) => (S0, Chunk[O])
  )(
    flushFn: S0 => (S0, Chunk[O])
  ): Transducer[I, O, S0] =
    new Transducer[I, O, S0]:
      def init: S0                          = initial
      def step(s: S0, i: I): (S0, Chunk[O]) = stepFn(s, i)
      def flush(s: S0): (S0, Chunk[O])      = flushFn(s)

  /** Stateful one-to-one fold (each input produces exactly one output). */
  def fold1[I, O, S0](initial: => S0)(
    stepFn: (S0, I) => (S0, O)
  )(
    flushFn: S0 => (S0, Chunk[O])
  ): Transducer[I, O, S0] =
    new Transducer[I, O, S0]:
      def init: S0                          = initial
      def step(s: S0, i: I): (S0, Chunk[O]) =
        val (s2, o) = stepFn(s, i)
        (s2, Chunk.single(o))
      def flush(s: S0): (S0, Chunk[O])      = flushFn(s)

  /** Stateful accumulator: output IS the state after each step. */
  def accumulate[I, S0](initial: => S0)(f: (S0, I) => S0): Transducer[I, S0, S0] =
    fold1[I, S0, S0](initial) { (s, i) =>
      val n = f(s, i); (n, n)
    }(s => (s, Chunk.empty))

  // ---------------------------------------------------------------------------
  //  Internal fused wrappers (for map / filter / contramap fusion)
  // ---------------------------------------------------------------------------

  // Internal wrappers use invariant type params to avoid variance conflicts.
  // The public extension methods handle the variance correctly.

  private final class Mapped[I, M, O, S](
    val base: Transducer[I, M, S],
    val f: M => O,
  ) extends Transducer[I, O, S]:
    def init: S                         = base.init
    def step(s: S, i: I): (S, Chunk[O]) =
      val (s2, ms) = base.step(s, i)
      (s2, ms.map(f))
    def flush(s: S): (S, Chunk[O])      =
      val (s2, ms) = base.flush(s)
      (s2, ms.map(f))

  private final class Filtered[I, O, S](
    val base: Transducer[I, O, S],
    val p: O => Boolean,
  ) extends Transducer[I, O, S]:
    def init: S                         = base.init
    def step(s: S, i: I): (S, Chunk[O]) =
      val (s2, os) = base.step(s, i)
      (s2, os.filter(p))
    def flush(s: S): (S, Chunk[O])      =
      val (s2, os) = base.flush(s)
      (s2, os.filter(p))

  private final class Contramapped[I, M, O, S](
    val base: Transducer[M, O, S],
    val g: I => M,
  ) extends Transducer[I, O, S]:
    def init: S                         = base.init
    def step(s: S, i: I): (S, Chunk[O]) = base.step(s, g(i))
    def flush(s: S): (S, Chunk[O])      = base.flush(s)

  // ---------------------------------------------------------------------------
  //  Extension methods (combinators)
  // ---------------------------------------------------------------------------

  extension [I, O, S](self: Transducer[I, O, S])

    // --- Mapping (with fusion) -----------------------------------------------

    /** Post-transform outputs. Fuses adjacent maps into one function. */
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

    /** Bidirectional transform. Fuses both directions. */
    def dimap[I2, O2](pre: I2 => I, post: O => O2): Transducer[I2, O2, S] =
      self.contramap(pre).map(post)

    /** Post-filter outputs. Fuses adjacent filters. */
    def filter(p: O => Boolean): Transducer[I, O, S] =
      self match
        case f: Filtered[I, O, S] @unchecked =>
          Filtered(f.base, o => f.p(o) && p(o))
        case _                               =>
          Filtered(self, p)

    /** Post-flatMap outputs. */
    def mapChunk[O2](f: Chunk[O] => Chunk[O2]): Transducer[I, O2, S] =
      new Transducer[I, O2, S]:
        def init: S                          = self.init
        def step(s: S, i: I): (S, Chunk[O2]) =
          val (s2, os) = self.step(s, i)
          (s2, f(os))
        def flush(s: S): (S, Chunk[O2])      =
          val (s2, os) = self.flush(s)
          (s2, f(os))

    // --- Sequential composition (>>>) ----------------------------------------

    /** Pipe output of `self` into input of `that`. State is merged via [[StateMerge]]. */
    def andThen[O2, S2, SOut](that: Transducer[O, O2, S2])(using sm: StateMerge.Aux[S, S2, SOut]): Transducer[I, O2, SOut] =
      new Transducer[I, O2, SOut]:
        def init: SOut = sm.merge(self.init, that.init)

        def step(s: SOut, i: I): (SOut, Chunk[O2]) =
          val s1             = sm.left(s)
          val s2             = sm.right(s)
          val (s1Next, mids) = self.step(s1, i)
          var s2Cur          = s2
          val builder        = ChunkBuilder.make[O2]()
          var idx            = 0
          while idx < mids.length do
            val (s2Next, os) = that.step(s2Cur, mids(idx))
            s2Cur = s2Next
            os.foreach(o => builder += o)
            idx += 1
          (sm.merge(s1Next, s2Cur), builder.result())

        def flush(s: SOut): (SOut, Chunk[O2]) =
          val s1              = sm.left(s)
          val s2              = sm.right(s)
          val (s1Next, mids)  = self.flush(s1)
          var s2Cur           = s2
          val builder         = ChunkBuilder.make[O2]()
          var idx             = 0
          while idx < mids.length do
            val (s2Next, os) = that.step(s2Cur, mids(idx))
            s2Cur = s2Next
            os.foreach(o => builder += o)
            idx += 1
          val (s2Final, tail) = that.flush(s2Cur)
          tail.foreach(o => builder += o)
          (sm.merge(s1Next, s2Final), builder.result())

    /** Operator alias for [[andThen]]. */
    def >>>[O2, S2, SOut](that: Transducer[O, O2, S2])(using StateMerge.Aux[S, S2, SOut]): Transducer[I, O2, SOut] =
      andThen(that)

    // --- Parallel / fanout (&&&) ---------------------------------------------

    /** Run both transducers on the same input. Output is paired. State is merged. */
    def fanout[O2, S2, SOut](that: Transducer[I, O2, S2])(using sm: StateMerge.Aux[S, S2, SOut]): Transducer[I, (O, O2), SOut] =
      new Transducer[I, (O, O2), SOut]:
        def init: SOut = sm.merge(self.init, that.init)

        def step(s: SOut, i: I): (SOut, Chunk[(O, O2)]) =
          val (s1Next, os1) = self.step(sm.left(s), i)
          val (s2Next, os2) = that.step(sm.right(s), i)
          val n             = math.min(os1.length, os2.length)
          val builder       = ChunkBuilder.make[(O, O2)]()
          var idx           = 0
          while idx < n do
            builder += ((os1(idx), os2(idx)))
            idx += 1
          (sm.merge(s1Next, s2Next), builder.result())

        def flush(s: SOut): (SOut, Chunk[(O, O2)]) =
          val (s1Next, os1) = self.flush(sm.left(s))
          val (s2Next, os2) = that.flush(sm.right(s))
          val n             = math.min(os1.length, os2.length)
          val builder       = ChunkBuilder.make[(O, O2)]()
          var idx           = 0
          while idx < n do
            builder += ((os1(idx), os2(idx)))
            idx += 1
          (sm.merge(s1Next, s2Next), builder.result())

    /** Operator alias for [[fanout]]. */
    def &&&[O2, S2, SOut](that: Transducer[I, O2, S2])(using StateMerge.Aux[S, S2, SOut]): Transducer[I, (O, O2), SOut] =
      fanout(that)

    // --- Compilation / execution ---------------------------------------------

    /** Run on an in-memory collection. Returns (final state, all outputs). */
    def runChunk(inputs: Iterable[I]): (S, Chunk[O]) =
      var s          = self.init
      val builder    = ChunkBuilder.make[O]()
      inputs.foreach { i =>
        val (s2, os) = self.step(s, i)
        s = s2
        os.foreach(o => builder += o)
      }
      val (sf, tail) = self.flush(s)
      tail.foreach(o => builder += o)
      (sf, builder.result())

    /** Run, discarding final state. */
    def run(inputs: Iterable[I]): Chunk[O] = runChunk(inputs)._2

    /** Compile to a ZIO `ZChannel`. */
    def toChannel: ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], Unit] =
      ZChannel.unwrap {
        zio.ZIO.succeed {
          var s: S = self.init

          def loop: ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], Unit] =
            ZChannel.readWith(
              (chunk: Chunk[I]) =>
                val (s2, out) = self.stepChunk(s, chunk)
                s = s2
                if out.isEmpty then loop
                else ZChannel.write(out) *> loop
              ,
              (_: Any) => ZChannel.unit,
              (_: Any) =>
                val (sf, tail) = self.flush(s)
                s = sf
                if tail.isEmpty then ZChannel.unit else ZChannel.write(tail),
            )

          loop
        }
      }

    /** Compile to a ZIO `ZPipeline`. */
    def toPipeline: ZPipeline[Any, Nothing, I, O] =
      ZPipeline.fromChannel(self.toChannel)

  // ---------------------------------------------------------------------------
  //  Batteries-included transducers
  // ---------------------------------------------------------------------------

  /** Count elements. State is a Record with a "count" field. */
  def counter[A]: Transducer[A, Long, Record["count" ~ Long]] =
    type S = Record["count" ~ Long]
    fold1[A, Long, S]((Record.empty & ("count" ~ 0L)).asInstanceOf[S]) { (state, _) =>
      val next  = state.count + 1
      val nextS = (Record.empty & ("count" ~ next)).asInstanceOf[S]
      (nextS, next)
    }(s => (s, Chunk.empty))

  /** Running byte total. State is a Record with a "totalBytes" field. */
  def byteCounter: Transducer[Chunk[Byte], Long, Record["totalBytes" ~ Long]] =
    type S = Record["totalBytes" ~ Long]
    fold1[Chunk[Byte], Long, S]((Record.empty & ("totalBytes" ~ 0L)).asInstanceOf[S]) { (state, bytes) =>
      val next  = state.totalBytes + bytes.length
      val nextS = (Record.empty & ("totalBytes" ~ next)).asInstanceOf[S]
      (nextS, next)
    }(s => (s, Chunk.empty))

  /** Running sum. State is a Record with a "sum" field. */
  def summer[A: kyo.Tag](using num: Numeric[A]): Transducer[A, A, Record["sum" ~ A]] =
    type S = Record["sum" ~ A]
    fold1[A, A, S]((Record.empty & ("sum" ~ num.zero)).asInstanceOf[S]) { (state, a) =>
      val next  = num.plus(state.sum, a)
      val nextS = (Record.empty & ("sum" ~ next)).asInstanceOf[S]
      (nextS, next)
    }(s => (s, Chunk.empty))

  /** Sliding window of last N elements. Uses tuple state (Vector is parameterized). */
  def window[A](size: Int): Transducer[A, Vector[A], Vector[A]] =
    val safeSize = math.max(1, size)
    fold1[A, Vector[A], Vector[A]](Vector.empty[A]) { (state, a) =>
      val next = if state.length >= safeSize then state.tail :+ a else state :+ a
      (next, next)
    }(s => (s, Chunk.empty))

end Transducer
