package graviton

import zio.*
import zio.stream.*
import zio.ChunkBuilder
import zio.stream.Take
import zio.prelude.fx.ZPure
import scala.compiletime.{erasedValue, summonFrom}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

/** A stateful stream transformer whose state is represented as a Tuple.
  * Stateless scans use `EmptyTuple` and therefore do not alter the state type
  * when composed. Composition operators are `transparent inline` to encourage
  * compile-time optimisation and eliminate intermediate allocations.
  */
sealed trait Scan[-I, +O]:
  type State <: Tuple

  def initial: State
  def step(state: State, in: I): (State, Chunk[O])
  def done(state: State): Chunk[O]

object Scan:
  type Aux[-I, +O, S <: Tuple] = Scan[I, O] { type State = S }

  // ---------------------- core operations ----------------------

  extension [I, O, S <: Tuple](self: Scan.Aux[I, O, S])
    transparent inline def toChannel: ZChannel[Any, Nothing, Chunk[
      I
    ], Any, Nothing, Take[Nothing, O], Any] =
      def loop(
          state: S
      ): ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Take[Nothing, O], Any] =
        ZChannel.readWith(
          in =>
            val (s, outs) = in.foldLeft((state, Chunk.empty[O])) { (acc, i) =>
              val (st, ch) = self.step(acc._1, i)
              (st, acc._2 ++ ch)
            }
            ZChannel.write(Take.chunk(outs)) *> loop(s)
          ,
          err => ZChannel.refailCause(err),
          _ =>
            ZChannel.write(Take.chunk(self.done(state))) *>
              ZChannel.write(Take.end) *>
              ZChannel.unit
        )
      loop(self.initial)

    transparent inline def toPipeline: ZPipeline[Any, Nothing, I, O] =
      ZPipeline.fromChannel(
        toChannel.mapOut(
          _.fold(
            Chunk.empty[O],
            cause => throw cause.squash,
            chunk => chunk
          )
        )
      )

    transparent inline def toSink: ZSink[Any, Nothing, I, Nothing, Chunk[O]] =
      toPipeline >>> ZSink.collectAll[O]

    transparent inline def map[O2](inline f: O => O2): Scan.Aux[I, O2, S] =
      statefulTuple(self.initial)((s: S, i: I) =>
        val (s2, o) = self.step(s, i)
        (s2, o.map(f))
      )(s => self.done(s).map(f))

    transparent inline def contramap[I2](
        inline f: I2 => I
    ): Scan.Aux[I2, O, S] =
      statefulTuple(self.initial)((s: S, i2: I2) => self.step(s, f(i2): I))(
        self.done
      )

    transparent inline def dimap[I2, O2](inline g: I2 => I)(
        inline f: O => O2
    ): Scan.Aux[I2, O2, S] =
      statefulTuple(self.initial)((s: S, i2: I2) =>
        val (s2, o) = self.step(s, g(i2): I)
        (s2, o.map(f))
      )(s => self.done(s).map(f))

    transparent inline def andThen[O2, S2 <: Tuple](
        inline that: Scan.Aux[O, O2, S2]
    ): Scan.Aux[I, O2, Tuple.Concat[S, S2]] =
      val sizeA = self.initial.productArity
      statefulTuple(self.initial ++ that.initial)((st: Tuple, i: I) =>
        val s1 = st.take(sizeA).asInstanceOf[S]
        val s2 = st.drop(sizeA).asInstanceOf[S2]
        val (s1b, o1) = self.step(s1, i)
        var s2b = s2
        val builder = ChunkBuilder.make[O2]()
        o1.foreach { o =>
          val (s2n, o2) = that.step(s2b, o)
          s2b = s2n
          builder ++= o2
        }
        ((s1b ++ s2b).asInstanceOf[Tuple.Concat[S, S2]], builder.result())
      ) { (st: Tuple) =>
        val s1 = st.take(sizeA).asInstanceOf[S]
        val s2 = st.drop(sizeA).asInstanceOf[S2]
        val interim = self.done(s1)
        var s2b = s2
        val builder = ChunkBuilder.make[O2]()
        interim.foreach { o =>
          val (s2n, o2) = that.step(s2b, o)
          s2b = s2n
          builder ++= o2
        }
        builder.result() ++ that.done(s2b)
      }

    transparent inline def flatMap[O2, S2 <: Tuple](
        inline that: Scan.Aux[O, O2, S2]
    ): Scan.Aux[I, O2, Tuple.Concat[S, S2]] =
      andThen(that)

    transparent inline def zip[O2, S2 <: Tuple](
        inline that: Scan.Aux[I, O2, S2]
    ): Scan.Aux[I, (O, O2), Tuple.Concat[S, S2]] =
      val sizeA = self.initial.productArity
      statefulTuple(self.initial ++ that.initial)((st: Tuple, i: I) =>
        val s1 = st.take(sizeA).asInstanceOf[S]
        val s2 = st.drop(sizeA).asInstanceOf[S2]
        val (s1b, o1) = self.step(s1, i)
        val (s2b, o2) = that.step(s2, i)
        ((s1b ++ s2b).asInstanceOf[Tuple.Concat[S, S2]], o1.zip(o2))
      ) { (st: Tuple) =>
        val s1 = st.take(sizeA).asInstanceOf[S]
        val s2 = st.drop(sizeA).asInstanceOf[S2]
        self.done(s1).zip(that.done(s2))
      }

    transparent inline def runAll(
        inputs: Iterable[I]
    ): (S, Chunk[O]) =
      var state = self.initial
      val builder = ChunkBuilder.make[O]()
      val it = inputs.iterator
      while it.hasNext do
        val (s, out) = self.step(state, it.next())
        state = s
        builder ++= out
      builder ++= self.done(state)
      (state, builder.result())

    transparent inline def runZPure(
        inputs: Iterable[I]
    ): ZPure[Nothing, S, S, Any, Nothing, Chunk[O]] =
      ZPure.modify { (st: S) =>
        var state = st
        val builder = ChunkBuilder.make[O]()
        val it = inputs.iterator
        while it.hasNext do
          val (s, out) = self.step(state, it.next())
          state = s
          builder ++= out
        builder ++= self.done(state)
        (builder.result(), state)
      }

  // ---------------------- Stateless implementations ----------------------

  sealed trait Stateless[-I, +O] extends Scan[I, O]:
    final type State = EmptyTuple
    final val initial: EmptyTuple = EmptyTuple

  private[Scan] final class StatelessChain[I, O](val fs: Chunk[Any => Any])
      extends Stateless[I, O]:
    def step(state: EmptyTuple, in: I) =
      var res: Any = in
      var i = 0
      while i < fs.length do
        res = fs(i)(res)
        i += 1
      (state, Chunk.single(res.asInstanceOf[O]))
    def done(state: EmptyTuple) = Chunk.empty
  end StatelessChain

  transparent inline def stateless1[I, O](inline f: I => O): Stateless[I, O] =
    new StatelessChain[I, O](Chunk.single(f.asInstanceOf[Any => Any]))

  transparent inline def stateless[I, O](
      inline f: I => Chunk[O]
  ): Stateless[I, O] =
    new Stateless[I, O]:
      def step(state: EmptyTuple, in: I) = (state, f(in))
      def done(state: EmptyTuple) = Chunk.empty

  // ---------------------- Stateful helpers ----------------------

  sealed trait Stateful[-I, +O, S <: Tuple] extends Scan[I, O]:
    final type State = S
    val initial: S

  transparent inline def stateful[I, O, S](init: S)(
      inline stepFn: (S, I) => (S, Chunk[O])
  )(inline doneFn: S => Chunk[O]): Stateful[I, O, S *: EmptyTuple] =
    new Stateful[I, O, S *: EmptyTuple]:
      val initial = init *: EmptyTuple
      def step(state: S *: EmptyTuple, in: I) =
        val (s, o) = stepFn(state.head, in)
        (s *: EmptyTuple, o)
      def done(state: S *: EmptyTuple) = doneFn(state.head)

  transparent inline def statefulTuple[I, O, S <: Tuple](init: S)(
      inline stepFn: (S, I) => (S, Chunk[O])
  )(inline doneFn: S => Chunk[O]): Stateful[I, O, S] =
    new Stateful[I, O, S]:
      val initial = init
      def step(state: S, in: I) = stepFn(state, in)
      def done(state: S) = doneFn(state)

  transparent inline def apply[I, O, S](init: S)(
      inline stepFn: (S, I) => (S, Chunk[O])
  )(inline doneFn: S => Chunk[O]): Stateful[I, O, S *: EmptyTuple] =
    stateful(init)(stepFn)(doneFn)

  // identity
  private object Identity extends Stateless[Any, Any]:
    def step(state: EmptyTuple, in: Any) = (state, Chunk.single(in))
    def done(state: EmptyTuple) = Chunk.empty

  transparent inline def identity[I]: Aux[I, I, EmptyTuple] =
    Identity.asInstanceOf[Aux[I, I, EmptyTuple]]

  transparent inline def lift[I, O](inline f: I => O): Aux[I, O, EmptyTuple] =
    stateless1(f)

  // ---------------------- Built-in scans ----------------------

  /** Counts the number of elements in the incoming stream. */
  val count: Aux[Any, Long, Long *: EmptyTuple] =
    stateful[Any, Long, Long](0L)((s, _) => (s + 1L, Chunk.empty))(s =>
      Chunk.single(s)
    )

  /** Computes a hash of all bytes using the provided algorithm. */
  def hash(algo: HashAlgorithm): Aux[Byte, Hash, AnyRef *: EmptyTuple] =
    algo match
      case HashAlgorithm.SHA256 | HashAlgorithm.SHA512 =>
        val md = java.security.MessageDigest.getInstance(algo match
          case HashAlgorithm.SHA256 => "SHA-256"
          case HashAlgorithm.SHA512 => "SHA-512"
          case _                    => "SHA-256"
        )
        stateful[Byte, Hash, java.security.MessageDigest](md)((m, b) => {
          m.update(b); (m, Chunk.empty)
        })(m =>
          Chunk.single(
            Hash(
              Chunk.fromArray(m.digest()).assume[MinLength[16] & MaxLength[64]],
              algo
            )
          )
        )
          .asInstanceOf[Aux[Byte, Hash, AnyRef *: EmptyTuple]]
      case HashAlgorithm.Blake3 =>
        val bl = io.github.rctcwyvrn.blake3.Blake3.newInstance()
        stateful[Byte, Hash, io.github.rctcwyvrn.blake3.Blake3](bl)((h, b) => {
          h.update(Array(b)); (h, Chunk.empty)
        })(h =>
          Chunk.single(
            Hash(
              Chunk.fromArray(h.digest()).assume[MinLength[16] & MaxLength[64]],
              algo
            )
          )
        )
          .asInstanceOf[Aux[Byte, Hash, AnyRef *: EmptyTuple]]

  /** Convenience scan that produces both hash and count in one pass. */
  def hashAndCount(
      algo: HashAlgorithm
  ): Aux[Byte, (Hash, Long), (AnyRef, Long)] =
    val initHasher: AnyRef = algo match
      case HashAlgorithm.SHA256 =>
        java.security.MessageDigest.getInstance("SHA-256")
      case HashAlgorithm.SHA512 =>
        java.security.MessageDigest.getInstance("SHA-512")
      case HashAlgorithm.Blake3 =>
        io.github.rctcwyvrn.blake3.Blake3.newInstance()
    statefulTuple((initHasher, 0L)) { (state, b: Byte) =>
      val (h, c) = state
      h match
        case md: java.security.MessageDigest       => md.update(b)
        case bl: io.github.rctcwyvrn.blake3.Blake3 => bl.update(Array(b))
      ((h, c + 1L), Chunk.empty)
    } { (state) =>
      val (h, c) = state
      val dig = h match
        case md: java.security.MessageDigest => Chunk.fromArray(md.digest())
        case bl: io.github.rctcwyvrn.blake3.Blake3 =>
          Chunk.fromArray(bl.digest())
      val digRef = dig.assume[MinLength[16] & MaxLength[64]]
      Chunk.single((Hash(digRef, algo), c))
    }

  // --------- chunking ---------

  final case class ChunkState[I](buffer: Chunk[I], cost: Int)

  def chunkBy[I](
      costFn: I => Int,
      threshold: Int
  ): Aux[I, Chunk[I], ChunkState[I] *: EmptyTuple] =
    stateful(ChunkState(Chunk.empty[I], 0)) { (st: ChunkState[I], i: I) =>
      val buf1 = st.buffer :+ i
      val cost1 = st.cost + costFn(i)
      if cost1 >= threshold then
        (ChunkState(Chunk.empty, 0), Chunk.single(buf1))
      else (ChunkState(buf1, cost1), Chunk.empty)
    } { st =>
      if st.buffer.isEmpty then Chunk.empty else Chunk.single(st.buffer)
    }

  def fixedSize[I](size: Int): Aux[I, Chunk[I], ChunkState[I] *: EmptyTuple] =
    chunkBy(_ => 1, size)
end Scan
