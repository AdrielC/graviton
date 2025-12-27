package graviton.core.scan

import kyo.Record
import kyo.Record.`~`
import kyo.Tag
import zio.{Chunk, ChunkBuilder}
import zio.stream.{ZChannel, ZPipeline}

/**
 * A "sicko" (minimal, direct) Scan: stateful stream transducer with lawful composition.
 *
 * - State `S` is a type member and (when composed) is a **kyo.Record** intersection.
 * - Each input element emits exactly one output (docs-style step semantics).
 * - `flush` can emit trailing outputs (typically 0 or 1).
 */
trait Scan[-I, +O]:
  type S <: Record[?]

  /** Fresh initial state for a new run. */
  def init(): S

  /** Process one input element, emitting exactly one output. */
  def step(state: S, input: I): (S, O)

  /** End-of-stream finalization (may emit trailing outputs). */
  def flush(state: S): (S, Chunk[O])

object Scan:
  type Aux[-I, +O, S0 <: Record[?]] = Scan[I, O] { type S = S0 }

  type FieldsOf[R <: Record[?]] = R match
    case Record[fields] => fields

  type Compose[SA <: Record[?], SB <: Record[?]] = Record[FieldsOf[SA] & FieldsOf[SB]]

  inline private def merge[SA <: Record[?], SB <: Record[?]](sa: SA, sb: SB): Compose[SA, SB] =
    (sa.asInstanceOf[Record[Any]] & sb.asInstanceOf[Record[Any]]).asInstanceOf[Compose[SA, SB]]

  type Pair[L <: String & Singleton, R <: String & Singleton, A, B] = Record[(L ~ A) & (R ~ B)]

  private inline def pack[L <: String & Singleton, R <: String & Singleton, A, B](a: A, b: B)(
    using ValueOf[L],
    ValueOf[R],
    Tag[A],
    Tag[B],
  ): Pair[L, R, A, B] =
    (Record.empty
      & (summon[ValueOf[L]].value ~ a)
      & (summon[ValueOf[R]].value ~ b)).asInstanceOf[Pair[L, R, A, B]]

  def id[A]: Aux[A, A, Record[Any]] =
    new Scan[A, A]:
      type S = Record[Any]
      def init(): S                               = Record.empty
      def step(state: S, input: A): (S, A)        = (state, input)
      def flush(state: S): (S, Chunk[A])          = (state, Chunk.empty)

  def pure[I, O](f: I => O): Aux[I, O, Record[Any]] =
    new Scan[I, O]:
      type S = Record[Any]
      def init(): S                               = Record.empty
      def step(state: S, input: I): (S, O)        = (state, f(input))
      def flush(state: S): (S, Chunk[O])          = (state, Chunk.empty)

  def fold[I, O, S0 <: Record[?]](
    initial: => S0
  )(
    step0: (S0, I) => (S0, O)
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

        def step(state: S, input: I): (S, O2) =
          val sa0      = state.asInstanceOf[SA]
          val sb0      = state.asInstanceOf[SB]
          val (sa1, m) = left.step(sa0, input)
          val (sb1, o) = right.step(sb0, m)
          (merge(sa1, sb1), o)

        def flush(state: S): (S, Chunk[O2]) =
          val sa0 = state.asInstanceOf[SA]
          val sb0 = state.asInstanceOf[SB]

          val (sa1, mids) = left.flush(sa0) // mids are fed through right.step
          var sb          = sb0
          val out         = ChunkBuilder.make[O2]()

          var idx = 0
          while idx < mids.length do
            val (nextSb, o2) = right.step(sb, mids(idx))
            sb = nextSb
            out += o2
            idx += 1

          val (sb2, tail) = right.flush(sb)
          out ++= tail

          (merge(sa1, sb2), out.result())

    def map[O2](f: O => O2): Aux[I, O2, SA] =
      new Scan[I, O2]:
        type S = SA
        def init(): S = left.init()

        def step(state: S, input: I): (S, O2) =
          val (s2, out) = left.step(state, input)
          (s2, f(out))

        def flush(state: S): (S, Chunk[O2]) =
          val (s2, out) = left.flush(state)
          (s2, out.map(f))

    def contramap[I2](g: I2 => I): Aux[I2, O, SA] =
      new Scan[I2, O]:
        type S = SA
        def init(): S = left.init()

        def step(state: S, input: I2): (S, O) =
          left.step(state, g(input))

        def flush(state: S): (S, Chunk[O]) =
          left.flush(state)

    def dimap[I2, O2](pre: I2 => I, post: O => O2): Aux[I2, O2, SA] =
      left.contramap(pre).map(post)

    inline def asField[Label <: String & Singleton](using ValueOf[Label], Tag[O]): Aux[I, Record[Label ~ O], SA] =
      left.map { o =>
        (Record.empty & (summon[ValueOf[Label]].value ~ o)).asInstanceOf[Record[Label ~ O]]
      }

    inline def labelled[Label <: String & Singleton]: ScanBranch[I, O, Label, SA] =
      ScanBranch(left)

    def runChunk(inputs: Iterable[I]): (SA, Chunk[O]) =
      var s: SA      = left.init()
      val out        = ChunkBuilder.make[O]()
      inputs.foreach { in =>
        val (s2, emitted) = left.step(s, in)
        s = s2
        out += emitted
      }
      val (sf, tail) = left.flush(s)
      out ++= tail
      (sf, out.result())

    def toChannel: ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], Unit] =
      ZChannel.unwrap {
        zio.ZIO.succeed {
          var s: SA = left.init()

          def loop: ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], Unit] =
            ZChannel.readWith(
              (chunk: Chunk[I]) =>
                val builder = ChunkBuilder.make[O]()
                var idx     = 0
                while idx < chunk.length do
                  val (s2, o) = left.step(s, chunk(idx))
                  s = s2
                  builder += o
                  idx += 1
                val outChunk = builder.result()
                if outChunk.isEmpty then loop
                else ZChannel.writeChunk(Chunk(outChunk)) *> loop
              ,
              (_: Any) => ZChannel.unit,
              (_: Any) =>
                val (sf, tail) = left.flush(s)
                s = sf
                if tail.isEmpty then ZChannel.unit else ZChannel.writeChunk(Chunk(tail)),
            )

          loop
        }
      }

    def toPipeline: ZPipeline[Any, Nothing, I, O] =
      ZPipeline.fromChannel(toChannel)

  final case class ScanBranch[I, O, Label <: String & Singleton, S0 <: Record[?]](scan: Aux[I, O, S0])

  object ScanBranch:
    type AutoLeft  = "_0"
    type AutoRight = "_1"

    def autoLeft[I, O, S0 <: Record[?]](scan: Aux[I, O, S0]): ScanBranch[I, O, AutoLeft, S0] =
      ScanBranch(scan)

    def autoRight[I, O, S0 <: Record[?]](scan: Aux[I, O, S0]): ScanBranch[I, O, AutoRight, S0] =
      ScanBranch(scan)

  extension [I, O, L <: String & Singleton, SA <: Record[?]](left: ScanBranch[I, O, L, SA])
    /** Fanout (broadcast) with a labelled left branch, auto-labelled right branch. */
    infix def &&&[O2, SB <: Record[?]](
      right: Aux[I, O2, SB]
    )(using ValueOf[L], Tag[O], Tag[O2]): Aux[I, Pair[L, ScanBranch.AutoRight, O, O2], Compose[SA, SB]] =
      left &&& ScanBranch.autoRight(right)

    /** Fanout (broadcast) with labelled left/right branches. */
    infix def &&&[O2, R <: String & Singleton, SB <: Record[?]](
      right: ScanBranch[I, O2, R, SB]
    )(using ValueOf[L], ValueOf[R], Tag[O], Tag[O2]): Aux[I, Pair[L, R, O, O2], Compose[SA, SB]] =
      new Scan[I, Pair[L, R, O, O2]]:
        type S = Compose[SA, SB]
        def init(): S =
          merge(left.scan.init(), right.scan.init())

        def step(state: S, input: I): (S, Pair[L, R, O, O2]) =
          val sa0      = state.asInstanceOf[SA]
          val sb0      = state.asInstanceOf[SB]
          val (sa1, a) = left.scan.step(sa0, input)
          val (sb1, b) = right.scan.step(sb0, input)
          (merge(sa1, sb1), pack[L, R, O, O2](a, b))

        def flush(state: S): (S, Chunk[Pair[L, R, O, O2]]) =
          val sa0           = state.asInstanceOf[SA]
          val sb0           = state.asInstanceOf[SB]
          val (sa1, leftT)  = left.scan.flush(sa0)
          val (sb1, rightT) = right.scan.flush(sb0)
          val n             = math.min(leftT.length, rightT.length)
          val out           = ChunkBuilder.make[Pair[L, R, O, O2]]()
          var idx           = 0
          while idx < n do
            out += pack[L, R, O, O2](leftT(idx), rightT(idx))
            idx += 1
          (merge(sa1, sb1), out.result())

  extension [I, O, SA <: Record[?]](left: Aux[I, O, SA])
    /** Fanout (broadcast) with auto labels `_0` / `_1`. */
    infix def &&&[O2, SB <: Record[?]](
      right: Aux[I, O2, SB]
    )(using Tag[O], Tag[O2]): Aux[I, Pair[ScanBranch.AutoLeft, ScanBranch.AutoRight, O, O2], Compose[SA, SB]] =
      ScanBranch.autoLeft(left) &&& ScanBranch.autoRight(right)

    /** Parallel on tuples (docs-style `+++`): (A, C) => (B, D). */
    infix def +++[I2, O2, SB <: Record[?]](right: Aux[I2, O2, SB]): Aux[(I, I2), (O, O2), Compose[SA, SB]] =
      new Scan[(I, I2), (O, O2)]:
        type S = Compose[SA, SB]
        def init(): S =
          merge(left.init(), right.init())

        def step(state: S, input: (I, I2)): (S, (O, O2)) =
          val sa0      = state.asInstanceOf[SA]
          val sb0      = state.asInstanceOf[SB]
          val (a, i2)  = input
          val (sa1, o) = left.step(sa0, a)
          val (sb1, p) = right.step(sb0, i2)
          (merge(sa1, sb1), (o, p))

        def flush(state: S): (S, Chunk[(O, O2)]) =
          val sa0           = state.asInstanceOf[SA]
          val sb0           = state.asInstanceOf[SB]
          val (sa1, leftT)  = left.flush(sa0)
          val (sb1, rightT) = right.flush(sb0)
          val n             = math.min(leftT.length, rightT.length)
          val out           = ChunkBuilder.make[(O, O2)]()
          var idx           = 0
          while idx < n do
            out += ((leftT(idx), rightT(idx)))
            idx += 1
          (merge(sa1, sb1), out.result())

    /** Choice (docs-style `|||`): Either[A,C] => Either[B,D]. */
    infix def |||[I2, O2, SB <: Record[?]](right: Aux[I2, O2, SB]): Aux[Either[I, I2], Either[O, O2], Compose[SA, SB]] =
      new Scan[Either[I, I2], Either[O, O2]]:
        type S = Compose[SA, SB]
        def init(): S =
          merge(left.init(), right.init())

        def step(state: S, input: Either[I, I2]): (S, Either[O, O2]) =
          val sa0 = state.asInstanceOf[SA]
          val sb0 = state.asInstanceOf[SB]
          input match
            case Left(a)  =>
              val (sa1, o) = left.step(sa0, a)
              (merge(sa1, sb0), Left(o))
            case Right(b) =>
              val (sb1, o) = right.step(sb0, b)
              (merge(sa0, sb1), Right(o))

        def flush(state: S): (S, Chunk[Either[O, O2]]) =
          val sa0          = state.asInstanceOf[SA]
          val sb0          = state.asInstanceOf[SB]
          val (sa1, leftT) = left.flush(sa0)
          val (sb1, rT)    = right.flush(sb0)
          val out          =
            leftT.map(Left(_)) ++ rT.map(Right(_))
          (merge(sa1, sb1), out)

    def first[X]: Aux[(I, X), (O, X), SA] =
      new Scan[(I, X), (O, X)]:
        type S = SA
        def init(): S = left.init()

        def step(state: S, input: (I, X)): (S, (O, X)) =
          val (s2, o) = left.step(state, input._1)
          (s2, (o, input._2))

        def flush(state: S): (S, Chunk[(O, X)]) =
          val (s2, tail) = left.flush(state)
          // No access to `X` at stream end: drop tail outputs.
          (s2, Chunk.empty)

    def second[X]: Aux[(X, I), (X, O), SA] =
      new Scan[(X, I), (X, O)]:
        type S = SA
        def init(): S = left.init()

        def step(state: S, input: (X, I)): (S, (X, O)) =
          val (s2, o) = left.step(state, input._2)
          (s2, (input._1, o))

        def flush(state: S): (S, Chunk[(X, O)]) =
          val (s2, tail) = left.flush(state)
          // No access to `X` at stream end: drop tail outputs.
          (s2, Chunk.empty)

