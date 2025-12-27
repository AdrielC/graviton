package graviton.core.scan

import zio.{Chunk, ChunkBuilder}
import zio.stream.{ZChannel, ZPipeline}
import kyo.Record
import scala.util.NotGiven

/**
 * A "sicko" (minimal, direct) Scan: stateful stream transducer with lawful composition.
 *
 * - State is tracked at the type level and defaults to `NoState` (empty).
 * - Capabilities are tracked at the type level via the `C` parameter (like `FreeArrow`).
 * - Each input element emits exactly one output (docs-style step semantics).
 * - `flush` can emit trailing outputs (typically 0 or 1).
 */
trait Scan[-I, +O, S, +C]:
  /** Fresh initial state for a new run. */
  def init(): S

  /** Process one input element, emitting exactly one output. */
  def step(state: S, input: I): (S, O)

  /** End-of-stream finalization (may emit trailing outputs). */
  def flush(state: S): (S, Chunk[O])

object Scan:
  type Aux[-I, +O, S, C] = Scan[I, O, S, C]

  /** Empty state for "stateless" scans. */
  type NoState = Unit

  trait StateCompose[SA, SB, Out]:
    def make(sa: SA, sb: SB): Out
    def left(out: Out): SA
    def right(out: Out): SB

  object StateCompose extends StateComposeLowPriority:

    given bothNoState: StateCompose[NoState, NoState, NoState] with
      def make(sa: NoState, sb: NoState): NoState = ()
      def left(out: NoState): NoState             = ()
      def right(out: NoState): NoState            = ()

    given leftId[SB](using NotGiven[SB =:= NoState]): StateCompose[NoState, SB, SB] with
      def make(sa: NoState, sb: SB): SB = sb
      def left(out: SB): NoState        = ()
      def right(out: SB): SB            = out

    given rightId[SA](using NotGiven[SA =:= NoState]): StateCompose[SA, NoState, SA] with
      def make(sa: SA, sb: NoState): SA = sa
      def left(out: SA): SA             = out
      def right(out: SA): NoState       = ()

  trait StateComposeLowPriority:
    given pair[SA, SB]: StateCompose[SA, SB, (SA, SB)] with
      def make(sa: SA, sb: SB): (SA, SB) = (sa, sb)
      def left(out: (SA, SB)): SA        = out._1
      def right(out: (SA, SB)): SB       = out._2

  /** Capability union: identity is `Any`; record/record merges by intersection (like `FreeArrow`). */
  type CapUnion[A, B] = (A, B) match
    case (Any, b)                 => b
    case (a, Any)                 => a
    case (Record[fa], Record[fb]) => Record[fa & fb]
    case _                        => A & B

  inline private def composeState[SA, SB, Out](sa: SA, sb: SB)(using sc: StateCompose[SA, SB, Out]): Out =
    sc.make(sa, sb)

  def id[A]: Aux[A, A, NoState, Any] =
    new Scan[A, A, NoState, Any]:
      def init(): NoState                              = ()
      def step(state: NoState, input: A): (NoState, A) = (state, input)
      def flush(state: NoState): (NoState, Chunk[A])   = (state, Chunk.empty)

  def pure[I, O](f: I => O): Aux[I, O, NoState, Any] =
    new Scan[I, O, NoState, Any]:
      def init(): NoState                              = ()
      def step(state: NoState, input: I): (NoState, O) = (state, f(input))
      def flush(state: NoState): (NoState, Chunk[O])   = (state, Chunk.empty)

  def fold[I, O, S0](
    initial: => S0
  )(
    step0: (S0, I) => (S0, O)
  )(
    flush0: S0 => (S0, Chunk[O])
  ): Aux[I, O, S0, Any] =
    new Scan[I, O, S0, Any]:
      def init(): S0                = initial
      def step(state: S0, input: I) = step0(state, input)
      def flush(state: S0)          = flush0(state)

  final case class Field[Label <: String & Singleton, A](label: Label, value: A) derives CanEqual
  object Field:
    inline def apply[Label <: String & Singleton, A](value: A)(using v: ValueOf[Label]): Field[Label, A] =
      Field(v.value, value)

  final case class Fields2[L <: String & Singleton, R <: String & Singleton, A, B](left: Field[L, A], right: Field[R, B]) derives CanEqual

  final case class ScanBranch[I, O, Label <: String & Singleton, S, C](scan: Aux[I, O, S, C])

  object ScanBranch:
    type AutoLeft  = "_0"
    type AutoRight = "_1"

    inline def autoLeft[I, O, S, C](scan: Aux[I, O, S, C]): ScanBranch[I, O, AutoLeft, S, C] =
      ScanBranch(scan)

    inline def autoRight[I, O, S, C](scan: Aux[I, O, S, C]): ScanBranch[I, O, AutoRight, S, C] =
      ScanBranch(scan)

  extension [I, O, S, C](self: Aux[I, O, S, C])
    /** Change the capability type parameter without changing runtime behavior. */
    def withCaps[C2]: Aux[I, O, S, C2] =
      new Scan[I, O, S, C2]:
        def init(): S                        = self.init()
        def step(state: S, input: I): (S, O) = self.step(state, input)
        def flush(state: S): (S, Chunk[O])   = self.flush(state)

    transparent inline infix def >>>[O2, S2, C2, SOut](
      right: Aux[O, O2, S2, C2]
    )(using sc: StateCompose[S, S2, SOut]): Aux[I, O2, SOut, CapUnion[C, C2]] =
      new Scan[I, O2, SOut, CapUnion[C, C2]]:
        def init(): SOut =
          sc.make(self.init(), right.init())

        def step(state: SOut, input: I): (SOut, O2) =
          val sa0      = sc.left(state)
          val sb0      = sc.right(state)
          val (sa1, m) = self.step(sa0, input)
          val (sb1, o) = right.step(sb0, m)
          (sc.make(sa1, sb1), o)

        def flush(state: SOut): (SOut, Chunk[O2]) =
          val sa0 = sc.left(state)
          val sb0 = sc.right(state)

          val (sa1, mids) = self.flush(sa0)
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

          (sc.make(sa1, sb2), out.result())

    def map[O2](f: O => O2): Aux[I, O2, S, C] =
      new Scan[I, O2, S, C]:
        def init(): S                         = self.init()
        def step(state: S, input: I): (S, O2) =
          val (s2, out) = self.step(state, input)
          (s2, f(out))
        def flush(state: S): (S, Chunk[O2])   =
          val (s2, out) = self.flush(state)
          (s2, out.map(f))

    def contramap[I2](g: I2 => I): Aux[I2, O, S, C] =
      new Scan[I2, O, S, C]:
        def init(): S                         = self.init()
        def step(state: S, input: I2): (S, O) = self.step(state, g(input))
        def flush(state: S): (S, Chunk[O])    = self.flush(state)

    def dimap[I2, O2](pre: I2 => I, post: O => O2): Aux[I2, O2, S, C] =
      self.contramap(pre).map(post)

    inline def labelled[Label <: String & Singleton]: ScanBranch[I, O, Label, S, C] =
      ScanBranch(self)

    def runChunk(inputs: Iterable[I]): (S, Chunk[O]) =
      var s: S       = self.init()
      val out        = ChunkBuilder.make[O]()
      inputs.foreach { in =>
        val (s2, emitted) = self.step(s, in)
        s = s2
        out += emitted
      }
      val (sf, tail) = self.flush(s)
      out ++= tail
      (sf, out.result())

    def toChannel: ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], Unit] =
      ZChannel.unwrap {
        zio.ZIO.succeed {
          var s: S = self.init()

          def loop: ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], Unit] =
            ZChannel.readWith(
              (chunk: Chunk[I]) =>
                val builder  = ChunkBuilder.make[O]()
                var idx      = 0
                while idx < chunk.length do
                  val (s2, o) = self.step(s, chunk(idx))
                  s = s2
                  builder += o
                  idx += 1
                val outChunk = builder.result()
                if outChunk.isEmpty then loop
                else ZChannel.writeChunk(Chunk(outChunk)) *> loop
              ,
              (_: Any) => ZChannel.unit,
              (_: Any) =>
                val (sf, tail) = self.flush(s)
                s = sf
                if tail.isEmpty then ZChannel.unit else ZChannel.writeChunk(Chunk(tail)),
            )

          loop
        }
      }

    def toPipeline: ZPipeline[Any, Nothing, I, O] =
      ZPipeline.fromChannel(toChannel)

  extension [I, O, S, C](left: Aux[I, O, S, C])
    transparent inline infix def &&&[O2, S2, C2, SOut](
      right: Aux[I, O2, S2, C2]
    )(using sc: StateCompose[S, S2, SOut]): Aux[I, (O, O2), SOut, CapUnion[C, C2]] =
      new Scan[I, (O, O2), SOut, CapUnion[C, C2]]:
        def init(): SOut =
          sc.make(left.init(), right.init())

        def step(state: SOut, input: I): (SOut, (O, O2)) =
          val sa0      = sc.left(state)
          val sb0      = sc.right(state)
          val (sa1, a) = left.step(sa0, input)
          val (sb1, b) = right.step(sb0, input)
          (sc.make(sa1, sb1), (a, b))

        def flush(state: SOut): (SOut, Chunk[(O, O2)]) =
          val sa0           = sc.left(state)
          val sb0           = sc.right(state)
          val (sa1, leftT)  = left.flush(sa0)
          val (sb1, rightT) = right.flush(sb0)
          val n             = math.min(leftT.length, rightT.length)
          val out           = ChunkBuilder.make[(O, O2)]()
          var idx           = 0
          while idx < n do
            out += ((leftT(idx), rightT(idx)))
            idx += 1
          (sc.make(sa1, sb1), out.result())

    transparent inline infix def +++[I2, O2, S2, C2, SOut](
      right: Aux[I2, O2, S2, C2]
    )(using sc: StateCompose[S, S2, SOut]): Aux[(I, I2), (O, O2), SOut, CapUnion[C, C2]] =
      new Scan[(I, I2), (O, O2), SOut, CapUnion[C, C2]]:
        def init(): SOut =
          sc.make(left.init(), right.init())

        def step(state: SOut, input: (I, I2)): (SOut, (O, O2)) =
          val sa0      = sc.left(state)
          val sb0      = sc.right(state)
          val (a, i2)  = input
          val (sa1, o) = left.step(sa0, a)
          val (sb1, p) = right.step(sb0, i2)
          (sc.make(sa1, sb1), (o, p))

        def flush(state: SOut): (SOut, Chunk[(O, O2)]) =
          val sa0           = sc.left(state)
          val sb0           = sc.right(state)
          val (sa1, leftT)  = left.flush(sa0)
          val (sb1, rightT) = right.flush(sb0)
          val n             = math.min(leftT.length, rightT.length)
          val out           = ChunkBuilder.make[(O, O2)]()
          var idx           = 0
          while idx < n do
            out += ((leftT(idx), rightT(idx)))
            idx += 1
          (sc.make(sa1, sb1), out.result())

    transparent inline infix def |||[I2, O2, S2, C2, SOut](
      right: Aux[I2, O2, S2, C2]
    )(using sc: StateCompose[S, S2, SOut]): Aux[Either[I, I2], Either[O, O2], SOut, CapUnion[C, C2]] =
      new Scan[Either[I, I2], Either[O, O2], SOut, CapUnion[C, C2]]:
        def init(): SOut =
          sc.make(left.init(), right.init())

        def step(state: SOut, input: Either[I, I2]): (SOut, Either[O, O2]) =
          val sa0 = sc.left(state)
          val sb0 = sc.right(state)
          input match
            case Left(a)  =>
              val (sa1, o) = left.step(sa0, a)
              (sc.make(sa1, sb0), Left(o))
            case Right(b) =>
              val (sb1, o) = right.step(sb0, b)
              (sc.make(sa0, sb1), Right(o))

        def flush(state: SOut): (SOut, Chunk[Either[O, O2]]) =
          val sa0          = sc.left(state)
          val sb0          = sc.right(state)
          val (sa1, leftT) = left.flush(sa0)
          val (sb1, rT)    = right.flush(sb0)
          val out          = leftT.map(Left(_)) ++ rT.map(Right(_))
          (sc.make(sa1, sb1), out)

    def first[X]: Aux[(I, X), (O, X), S, C] =
      new Scan[(I, X), (O, X), S, C]:
        def init(): S                                  = left.init()
        def step(state: S, input: (I, X)): (S, (O, X)) =
          val (s2, o) = left.step(state, input._1)
          (s2, (o, input._2))
        def flush(state: S): (S, Chunk[(O, X)])        =
          val (s2, _) = left.flush(state)
          (s2, Chunk.empty)

    def second[X]: Aux[(X, I), (X, O), S, C] =
      new Scan[(X, I), (X, O), S, C]:
        def init(): S                                  = left.init()
        def step(state: S, input: (X, I)): (S, (X, O)) =
          val (s2, o) = left.step(state, input._2)
          (s2, (input._1, o))
        def flush(state: S): (S, Chunk[(X, O)])        =
          val (s2, _) = left.flush(state)
          (s2, Chunk.empty)

  extension [I, O, L <: String & Singleton, S, C](left: ScanBranch[I, O, L, S, C])
    transparent inline infix def &&&[O2, R <: String & Singleton, S2, C2, SOut](
      right: ScanBranch[I, O2, R, S2, C2]
    )(using vl: ValueOf[L], vr: ValueOf[R], sc: StateCompose[S, S2, SOut]): Aux[I, Fields2[L, R, O, O2], SOut, CapUnion[C, C2]] =
      new Scan[I, Fields2[L, R, O, O2], SOut, CapUnion[C, C2]]:
        def init(): SOut =
          sc.make(left.scan.init(), right.scan.init())

        def step(state: SOut, input: I): (SOut, Fields2[L, R, O, O2]) =
          val sa0      = sc.left(state)
          val sb0      = sc.right(state)
          val (sa1, a) = left.scan.step(sa0, input)
          val (sb1, b) = right.scan.step(sb0, input)
          (
            sc.make(sa1, sb1),
            Fields2(Field[L, O](a)(using vl), Field[R, O2](b)(using vr)),
          )

        def flush(state: SOut): (SOut, Chunk[Fields2[L, R, O, O2]]) =
          val sa0           = sc.left(state)
          val sb0           = sc.right(state)
          val (sa1, leftT)  = left.scan.flush(sa0)
          val (sb1, rightT) = right.scan.flush(sb0)
          val n             = math.min(leftT.length, rightT.length)
          val out           = ChunkBuilder.make[Fields2[L, R, O, O2]]()
          var idx           = 0
          while idx < n do
            out += Fields2(Field[L, O](leftT(idx))(using vl), Field[R, O2](rightT(idx))(using vr))
            idx += 1
          (sc.make(sa1, sb1), out.result())

    transparent inline infix def &&&[O2, S2, C2, SOut](
      right: Aux[I, O2, S2, C2]
    )(using vl: ValueOf[L], sc: StateCompose[S, S2, SOut]): Aux[I, Fields2[L, ScanBranch.AutoRight, O, O2], SOut, CapUnion[C, C2]] =
      left.&&&[O2, ScanBranch.AutoRight, S2, C2, SOut](ScanBranch.autoRight(right))(using vl, summon[ValueOf[ScanBranch.AutoRight]], sc)
