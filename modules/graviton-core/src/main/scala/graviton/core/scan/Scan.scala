package graviton.core.scan

import kyo.Record.`~`
import kyo.Tag
import kyo.Tag.given
import kyo.Record
import zio.{Chunk, ChunkBuilder}
import zio.stream.{ZChannel, ZPipeline}
import scala.compiletime.summonInline
import scala.compiletime.summonFrom

/**
 * A "sicko" (minimal, direct) Scan: stateful stream transducer with lawful composition.
 *
 * - State is tracked at the type level and defaults to `Nothing` (empty).
 * - When composed, state is internally wrapped into `kyo.Record` fields (`"_0"`, `"_1"`).
 * - Each input element emits exactly one output (docs-style step semantics).
 * - `flush` can emit trailing outputs (typically 0 or 1).
 */
trait Scan[-I, +O, S]:
  /** Fresh initial state for a new run. */
  def init(): S

  /** Process one input element, emitting exactly one output. */
  def step(state: S, input: I): (S, O)

  /** End-of-stream finalization (may emit trailing outputs). */
  def flush(state: S): (S, Chunk[O])

object Scan:
  type Aux[-I, +O, S] = Scan[I, O, S]

  type LeftLabel  = "_0"
  type RightLabel = "_1"

  type Pair[L <: String & Singleton, R <: String & Singleton, A, B] = Record[(L ~ A) & (R ~ B)]

  private sealed trait IsRec[A]
  private object IsRec:
    given [Fields]: IsRec[Record[Fields]] with {}

  /** Composition state: empty is `Any`, otherwise a record product. */
  type ComposeState[SA, SB] = (SA, SB) match
    case (Nothing, b)             => b
    case (a, Nothing)             => a
    case (Record[fa], Record[fb]) =>
      // When both component states are already records, we merge them by intersection.
      Record[fa & fb]
    case _                        =>
      // Otherwise, we store each state as a named cell inside a composite record.
      Pair[LeftLabel, RightLabel, SA, SB]

  inline private def pack[L <: String & Singleton, R <: String & Singleton, A, B](a: A, b: B)(
    using ValueOf[L],
    ValueOf[R],
    Tag[A],
    Tag[B],
  ): Pair[L, R, A, B] =
    (Record.empty & (summon[ValueOf[L]].value ~ a) & (summon[ValueOf[R]].value ~ b)).asInstanceOf[Pair[L, R, A, B]]

  inline private def composeState[SA, SB](sa: SA, sb: SB)(using ValueOf[LeftLabel], ValueOf[RightLabel]): ComposeState[SA, SB] =
    summonFrom {
      case _: (SA =:= Nothing) => sb.asInstanceOf[ComposeState[SA, SB]]
      case _                   =>
        summonFrom {
          case _: (SB =:= Nothing) => sa.asInstanceOf[ComposeState[SA, SB]]
          case _                   =>
            summonFrom {
              case _: IsRec[SA] =>
                summonFrom {
                  case _: IsRec[SB] =>
                    // Record/Record case: merge by record intersection.
                    (sa.asInstanceOf[Record[Any]] & sb.asInstanceOf[Record[Any]]).asInstanceOf[ComposeState[SA, SB]]
                  case _            =>
                    // Pair-case: we need tags to store values inside `kyo.Record`.
                    pack[LeftLabel, RightLabel, SA, SB](sa, sb)(
                      using summonInline[ValueOf[LeftLabel]],
                      summonInline[ValueOf[RightLabel]],
                      summonInline[Tag[SA]],
                      summonInline[Tag[SB]],
                    ).asInstanceOf[ComposeState[SA, SB]]
                }
              case _            =>
                // Pair-case: we need tags to store values inside `kyo.Record`.
                pack[LeftLabel, RightLabel, SA, SB](sa, sb)(
                  using summonInline[ValueOf[LeftLabel]],
                  summonInline[ValueOf[RightLabel]],
                  summonInline[Tag[SA]],
                  summonInline[Tag[SB]],
                ).asInstanceOf[ComposeState[SA, SB]]
            }
        }
    }

  inline private def leftState[SA, SB](s: ComposeState[SA, SB])(using ValueOf[LeftLabel]): SA =
    summonFrom {
      case _: (SA =:= Nothing) => s.asInstanceOf[SA]
      case _                   =>
        summonFrom {
          case _: (SB =:= Nothing) => s.asInstanceOf[SA]
          case _                   =>
            summonFrom {
              case _: IsRec[SA] =>
                summonFrom {
                  case _: IsRec[SB] =>
                    // Record/Record merged state: just view it as the left record.
                    s.asInstanceOf[SA]
                  case _            =>
                    given Tag[SA] = summonInline[Tag[SA]]
                    s.asInstanceOf[Pair[LeftLabel, RightLabel, SA, SB]].selectDynamic[LeftLabel, SA](summon[ValueOf[LeftLabel]].value)
                }
              case _            =>
                given Tag[SA] = summonInline[Tag[SA]]
                s.asInstanceOf[Pair[LeftLabel, RightLabel, SA, SB]].selectDynamic[LeftLabel, SA](summon[ValueOf[LeftLabel]].value)
            }
        }
    }

  inline private def rightState[SA, SB](s: ComposeState[SA, SB])(using ValueOf[RightLabel]): SB =
    summonFrom {
      case _: (SA =:= Nothing) => s.asInstanceOf[SB]
      case _                   =>
        summonFrom {
          case _: (SB =:= Nothing) => s.asInstanceOf[SB]
          case _                   =>
            summonFrom {
              case _: IsRec[SA] =>
                summonFrom {
                  case _: IsRec[SB] =>
                    // Record/Record merged state: just view it as the right record.
                    s.asInstanceOf[SB]
                  case _            =>
                    given Tag[SB] = summonInline[Tag[SB]]
                    s.asInstanceOf[Pair[LeftLabel, RightLabel, SA, SB]].selectDynamic[RightLabel, SB](summon[ValueOf[RightLabel]].value)
                }
              case _            =>
                given Tag[SB] = summonInline[Tag[SB]]
                s.asInstanceOf[Pair[LeftLabel, RightLabel, SA, SB]].selectDynamic[RightLabel, SB](summon[ValueOf[RightLabel]].value)
            }
        }
    }

  def id[A]: Aux[A, A, Nothing] =
    new Scan[A, A, Nothing]:
      def init(): Nothing                              = null.asInstanceOf[Nothing]
      def step(state: Nothing, input: A): (Nothing, A) = (state, input)
      def flush(state: Nothing): (Nothing, Chunk[A])   = (state, Chunk.empty)

  def pure[I, O](f: I => O): Aux[I, O, Nothing] =
    new Scan[I, O, Nothing]:
      def init(): Nothing                              = null.asInstanceOf[Nothing]
      def step(state: Nothing, input: I): (Nothing, O) = (state, f(input))
      def flush(state: Nothing): (Nothing, Chunk[O])   = (state, Chunk.empty)

  def fold[I, O, S0](
    initial: => S0
  )(
    step0: (S0, I) => (S0, O)
  )(
    flush0: S0 => (S0, Chunk[O])
  ): Aux[I, O, S0] =
    new Scan[I, O, S0]:
      type S = S0
      def init(): S                = initial
      def step(state: S, input: I) = step0(state, input)
      def flush(state: S)          = flush0(state)

  extension [I, O, SA](left: Aux[I, O, SA])
    transparent inline infix def >>>[O2, SB](right: Aux[O, O2, SB]): Aux[I, O2, ComposeState[SA, SB]] =
      new Scan[I, O2, ComposeState[SA, SB]]:
        type S = ComposeState[SA, SB]
        private given ValueOf[LeftLabel]  = ValueOf("_0")
        private given ValueOf[RightLabel] = ValueOf("_1")

        def init(): S =
          composeState(left.init(), right.init())

        def step(state: S, input: I): (S, O2) =
          val sa0      = leftState[SA, SB](state)
          val sb0      = rightState[SA, SB](state)
          val (sa1, m) = left.step(sa0, input)
          val (sb1, o) = right.step(sb0, m)
          (composeState(sa1, sb1), o)

        def flush(state: S): (S, Chunk[O2]) =
          val sa0 = leftState[SA, SB](state)
          val sb0 = rightState[SA, SB](state)

          val (sa1, mids) = left.flush(sa0)
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

          (composeState(sa1, sb2), out.result())

    def map[O2](f: O => O2): Aux[I, O2, SA] =
      new Scan[I, O2, SA]:
        type S = SA
        def init(): SA = left.init()

        def step(state: S, input: I): (S, O2) =
          val (s2, out) = left.step(state, input)
          (s2, f(out))

        def flush(state: S): (S, Chunk[O2]) =
          val (s2, out) = left.flush(state)
          (s2, out.map(f))

    def contramap[I2](g: I2 => I): Aux[I2, O, SA] =
      new Scan[I2, O, SA]:
        type S = SA
        def init(): SA = left.init()

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
                val builder  = ChunkBuilder.make[O]()
                var idx      = 0
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

  final case class ScanBranch[I, O, Label <: String & Singleton, S0](scan: Aux[I, O, S0])

  object ScanBranch:
    type AutoLeft  = "_0"
    type AutoRight = "_1"

    def autoLeft[I, O, S0](scan: Aux[I, O, S0]): ScanBranch[I, O, AutoLeft, S0] =
      ScanBranch(scan)

    def autoRight[I, O, S0](scan: Aux[I, O, S0]): ScanBranch[I, O, AutoRight, S0] =
      ScanBranch(scan)

  extension [I, O, L <: String & Singleton, SA](left: ScanBranch[I, O, L, SA])
    /** Fanout (broadcast) with a labelled left branch, auto-labelled right branch. */
    transparent inline infix def &&&[O2, SB](
      right: Aux[I, O2, SB]
    )(using ValueOf[L], Tag[O], Tag[O2]): Aux[I, Pair[L, ScanBranch.AutoRight, O, O2], ComposeState[SA, SB]] =
      left &&& ScanBranch.autoRight(right)

    /** Fanout (broadcast) with labelled left/right branches. */
    transparent inline infix def &&&[O2, R <: String & Singleton, SB](
      right: ScanBranch[I, O2, R, SB]
    )(using ValueOf[L], ValueOf[R], Tag[O], Tag[O2]): Aux[I, Pair[L, R, O, O2], ComposeState[SA, SB]] =
      new Scan[I, Pair[L, R, O, O2], ComposeState[SA, SB]]:
        type S = ComposeState[SA, SB]
        private given ValueOf[LeftLabel]  = ValueOf("_0")
        private given ValueOf[RightLabel] = ValueOf("_1")

        def init(): S =
          composeState(left.scan.init(), right.scan.init())

        def step(state: S, input: I): (S, Pair[L, R, O, O2]) =
          val sa0      = leftState[SA, SB](state)
          val sb0      = rightState[SA, SB](state)
          val (sa1, a) = left.scan.step(sa0, input)
          val (sb1, b) = right.scan.step(sb0, input)
          (composeState(sa1, sb1), pack[L, R, O, O2](a, b))

        def flush(state: S): (S, Chunk[Pair[L, R, O, O2]]) =
          val sa0           = leftState[SA, SB](state)
          val sb0           = rightState[SA, SB](state)
          val (sa1, leftT)  = left.scan.flush(sa0)
          val (sb1, rightT) = right.scan.flush(sb0)
          val n             = math.min(leftT.length, rightT.length)
          val out           = ChunkBuilder.make[Pair[L, R, O, O2]]()
          var idx           = 0
          while idx < n do
            out += pack[L, R, O, O2](leftT(idx), rightT(idx))
            idx += 1
          (composeState(sa1, sb1), out.result())

  extension [I, O, SA](left: Aux[I, O, SA])
    /** Fanout (broadcast) with auto labels `_0` / `_1`. */
    transparent inline infix def &&&[O2, SB](
      right: Aux[I, O2, SB]
    )(using Tag[O], Tag[O2]): Aux[I, Pair[ScanBranch.AutoLeft, ScanBranch.AutoRight, O, O2], ComposeState[SA, SB]] =
      ScanBranch.autoLeft(left) &&& ScanBranch.autoRight(right)

    /** Parallel on tuples (docs-style `+++`): (A, C) => (B, D). */
    transparent inline infix def +++[I2, O2, SB](
      right: Aux[I2, O2, SB]
    )(using Tag[SA], Tag[SB]): Aux[(I, I2), (O, O2), ComposeState[SA, SB]] =
      new Scan[(I, I2), (O, O2), ComposeState[SA, SB]]:
        type S = ComposeState[SA, SB]
        private given ValueOf[LeftLabel]  = ValueOf("_0")
        private given ValueOf[RightLabel] = ValueOf("_1")

        def init(): S =
          composeState(left.init(), right.init())

        def step(state: S, input: (I, I2)): (S, (O, O2)) =
          val sa0      = leftState[SA, SB](state)
          val sb0      = rightState[SA, SB](state)
          val (a, i2)  = input
          val (sa1, o) = left.step(sa0, a)
          val (sb1, p) = right.step(sb0, i2)
          (composeState(sa1, sb1), (o, p))

        def flush(state: S): (S, Chunk[(O, O2)]) =
          val sa0           = leftState[SA, SB](state)
          val sb0           = rightState[SA, SB](state)
          val (sa1, leftT)  = left.flush(sa0)
          val (sb1, rightT) = right.flush(sb0)
          val n             = math.min(leftT.length, rightT.length)
          val out           = ChunkBuilder.make[(O, O2)]()
          var idx           = 0
          while idx < n do
            out += ((leftT(idx), rightT(idx)))
            idx += 1
          (composeState(sa1, sb1), out.result())

    /** Choice (docs-style `|||`): Either[A,C] => Either[B,D]. */
    transparent inline infix def |||[I2, O2, SB](
      right: Aux[I2, O2, SB]
    )(using Tag[SA], Tag[SB]): Aux[Either[I, I2], Either[O, O2], ComposeState[SA, SB]] =
      new Scan[Either[I, I2], Either[O, O2], ComposeState[SA, SB]]:
        type S = ComposeState[SA, SB]
        private given ValueOf[LeftLabel]  = ValueOf("_0")
        private given ValueOf[RightLabel] = ValueOf("_1")

        def init(): S =
          composeState(left.init(), right.init())

        def step(state: S, input: Either[I, I2]): (S, Either[O, O2]) =
          val sa0 = leftState[SA, SB](state)
          val sb0 = rightState[SA, SB](state)
          input match
            case Left(a)  =>
              val (sa1, o) = left.step(sa0, a)
              (composeState(sa1, sb0), Left(o))
            case Right(b) =>
              val (sb1, o) = right.step(sb0, b)
              (composeState(sa0, sb1), Right(o))

        def flush(state: S): (S, Chunk[Either[O, O2]]) =
          val sa0          = leftState[SA, SB](state)
          val sb0          = rightState[SA, SB](state)
          val (sa1, leftT) = left.flush(sa0)
          val (sb1, rT)    = right.flush(sb0)
          val out          =
            leftT.map(Left(_)) ++ rT.map(Right(_))
          (composeState(sa1, sb1), out)

    def first[X]: Aux[(I, X), (O, X), SA] =
      new Scan[(I, X), (O, X), SA]:
        type S = SA
        def init(): SA = left.init()

        def step(state: S, input: (I, X)): (S, (O, X)) =
          val (s2, o) = left.step(state, input._1)
          (s2, (o, input._2))

        def flush(state: S): (S, Chunk[(O, X)]) =
          val (s2, tail) = left.flush(state)
          // No access to `X` at stream end: drop tail outputs.
          (s2, Chunk.empty)

    def second[X]: Aux[(X, I), (X, O), SA] =
      new Scan[(X, I), (X, O), SA]:
        type S = SA
        def init(): SA = left.init()

        def step(state: S, input: (X, I)): (S, (X, O)) =
          val (s2, o) = left.step(state, input._2)
          (s2, (input._1, o))

        def flush(state: S): (S, Chunk[(X, O)]) =
          val (s2, tail) = left.flush(state)
          // No access to `X` at stream end: drop tail outputs.
          (s2, Chunk.empty)
