package graviton.core.scan

import kyo.Record
import zio.{Chunk, ChunkBuilder}

/**
 * A "sicko" (minimal, direct) Scan: stateful stream transducer with lawful composition.
 *
 * - State `S` is a type member and (when composed) is a **kyo.Record** intersection.
 * - Each input element can emit 0..N outputs (`Chunk[O]`).
 * - `flush` can emit trailing outputs and update state once at end.
 */
trait Scan[-I, +O]:
  type S <: Record[?]

  /** Fresh initial state for a new run. */
  def init(): S

  /** Process one input element, possibly emitting many outputs. */
  def step(state: S, input: I): (S, Chunk[O])

  /** End-of-stream finalization (may emit trailing outputs). */
  def flush(state: S): (S, Chunk[O])

object Scan:
  type Aux[-I, +O, S0 <: Record[?]] = Scan[I, O] { type S = S0 }

  type FieldsOf[R <: Record[?]] = R match
    case Record[fields] => fields

  type Compose[SA <: Record[?], SB <: Record[?]] = Record[FieldsOf[SA] & FieldsOf[SB]]

  inline private def merge[SA <: Record[?], SB <: Record[?]](sa: SA, sb: SB): Compose[SA, SB] =
    (sa.asInstanceOf[Record[Any]] & sb.asInstanceOf[Record[Any]]).asInstanceOf[Compose[SA, SB]]

  def id[A]: Aux[A, A, Record[Any]] =
    new Scan[A, A]:
      type S = Record[Any]
      def init(): S                               = Record.empty
      def step(state: S, input: A): (S, Chunk[A]) = (state, Chunk.single(input))
      def flush(state: S): (S, Chunk[A])          = (state, Chunk.empty)

  def pure[I, O](f: I => O): Aux[I, O, Record[Any]] =
    new Scan[I, O]:
      type S = Record[Any]
      def init(): S                               = Record.empty
      def step(state: S, input: I): (S, Chunk[O]) = (state, Chunk.single(f(input)))
      def flush(state: S): (S, Chunk[O])          = (state, Chunk.empty)

  def empty[I, O]: Aux[I, O, Record[Any]] =
    new Scan[I, O]:
      type S = Record[Any]
      def init(): S                               = Record.empty
      def step(state: S, input: I): (S, Chunk[O]) = (state, Chunk.empty)
      def flush(state: S): (S, Chunk[O])          = (state, Chunk.empty)

  def filter[I](p: I => Boolean): Aux[I, I, Record[Any]] =
    new Scan[I, I]:
      type S = Record[Any]
      def init(): S                               = Record.empty
      def step(state: S, input: I): (S, Chunk[I]) = (state, if p(input) then Chunk.single(input) else Chunk.empty)
      def flush(state: S): (S, Chunk[I])          = (state, Chunk.empty)

  def flatMap[I, O](f: I => Chunk[O]): Aux[I, O, Record[Any]] =
    new Scan[I, O]:
      type S = Record[Any]
      def init(): S                               = Record.empty
      def step(state: S, input: I): (S, Chunk[O]) = (state, f(input))
      def flush(state: S): (S, Chunk[O])          = (state, Chunk.empty)

  def fold[I, O, S0 <: Record[?]](
    initial: => S0
  )(
    step0: (S0, I) => (S0, Chunk[O])
  )(
    flush0: S0 => (S0, Chunk[O])
  ): Aux[I, O, S0] =
    new Scan[I, O]:
      type S = S0
      def init(): S                = initial
      def step(state: S, input: I) = step0(state, input)
      def flush(state: S)          = flush0(state)

  extension [I, O, SA <: Record[?]](left: Aux[I, O, SA])
    infix def >>>[O2, SB <: Record[?]](right: Aux[O, O2, SB]): Aux[I, O2, Compose[SA, SB]] =
      new Scan[I, O2]:
        type S = Compose[SA, SB]

        def init(): S =
          merge(left.init(), right.init())

        def step(state: S, input: I): (S, Chunk[O2]) =
          val sa0         = state.asInstanceOf[SA]
          val sb0         = state.asInstanceOf[SB]
          val (sa1, mids) = left.step(sa0, input)

          var sb  = sb0
          val out = ChunkBuilder.make[O2]()
          var idx = 0
          while idx < mids.length do
            val (nextSb, chunk) = right.step(sb, mids(idx))
            sb = nextSb
            out ++= chunk
            idx += 1

          (merge(sa1, sb), out.result())

        def flush(state: S): (S, Chunk[O2]) =
          val sa0 = state.asInstanceOf[SA]
          val sb0 = state.asInstanceOf[SB]

          val (sa1, mids) = left.flush(sa0)

          var sb  = sb0
          val out = ChunkBuilder.make[O2]()
          var idx = 0
          while idx < mids.length do
            val (nextSb, chunk) = right.step(sb, mids(idx))
            sb = nextSb
            out ++= chunk
            idx += 1

          val (sb2, tail) = right.flush(sb)
          out ++= tail

          (merge(sa1, sb2), out.result())

    def map[O2](f: O => O2): Aux[I, O2, SA] =
      new Scan[I, O2]:
        type S = SA
        def init(): S = left.init()

        def step(state: S, input: I): (S, Chunk[O2]) =
          val (s2, out) = left.step(state, input)
          (s2, out.map(f))

        def flush(state: S): (S, Chunk[O2]) =
          val (s2, out) = left.flush(state)
          (s2, out.map(f))

    def contramap[I2](g: I2 => I): Aux[I2, O, SA] =
      new Scan[I2, O]:
        type S = SA
        def init(): S = left.init()

        def step(state: S, input: I2): (S, Chunk[O]) =
          left.step(state, g(input))

        def flush(state: S): (S, Chunk[O]) =
          left.flush(state)

    def dimap[I2, O2](pre: I2 => I, post: O => O2): Aux[I2, O2, SA] =
      left.contramap(pre).map(post)

    def runChunk(inputs: Iterable[I]): (SA, Chunk[O]) =
      var s: SA      = left.init()
      val out        = ChunkBuilder.make[O]()
      inputs.foreach { in =>
        val (s2, emitted) = left.step(s, in)
        s = s2
        out ++= emitted
      }
      val (sf, tail) = left.flush(s)
      out ++= tail
      (sf, out.result())
