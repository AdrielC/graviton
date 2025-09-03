package graviton

import zio.*
import zio.stream.*
import zio.ChunkBuilder

/**
 * A stateful stream transformer whose state is stored in a TypeRegister to
 * avoid tuple allocations. The state type `S` is a heterogeneous tuple that
 * grows type-safely when scans are composed. Stateless scans use `EmptyTuple`
 * so further compositions do not change the state type.
 */
final class Scan[S <: Tuple, -I, +O](
  val initial: S,
  val step: (TypeRegister[S], I) => Chunk[O],
  val onComplete: TypeRegister[S] => Chunk[O]
):

  private def processChunk(reg: TypeRegister[S], c: Chunk[I]): Chunk[O] =
    val builder = ChunkBuilder.make[O]()
    c.foreach { i => builder ++= step(reg, i) }
    builder.result()

  def toPipeline: ZPipeline[Any, Nothing, I, O] =
    def loop(reg: TypeRegister[S]): ZChannel[Any, Nothing, Chunk[I], Any, Nothing, Chunk[O], Any] =
      ZChannel.readWith(
        in => ZChannel.write(processChunk(reg, in)) *> loop(reg),
        err => ZChannel.refailCause(err),
        _   => ZChannel.write(onComplete(reg))
      )
    ZPipeline.fromChannel(loop(TypeRegister.init(initial)))

  def map[O2](f: O => O2): Scan[S, I, O2] =
    Scan(initial)((reg, i) => step(reg, i).map(f), reg => onComplete(reg).map(f))

  def contramap[I2](f: I2 => I): Scan[S, I2, O] =
    Scan(initial)((reg, i2) => step(reg, f(i2)), reg => onComplete(reg))

  def dimap[I2, O2](g: I2 => I)(f: O => O2): Scan[S, I2, O2] =
    Scan(initial)((reg, i2) => step(reg, g(i2)).map(f), reg => onComplete(reg).map(f))

  def andThen[S2 <: Tuple, O2](that: Scan[S2, O, O2]): Scan[Tuple.Concat[S, S2], I, O2] =
    val init = initial ++ that.initial
    val sizeS = initial.productArity
    Scan[Tuple.Concat[S, S2], I, O2](init)({ (reg, i) =>
      val (r1, r2) = reg.splitAt[S, S2](sizeS)
      val out1 = step(r1, i)
      val builder = ChunkBuilder.make[O2]()
      out1.foreach(o => builder ++= that.step(r2, o))
      builder.result()
    }, { reg =>
      val (r1, r2) = reg.splitAt[S, S2](sizeS)
      val interim = onComplete(r1)
      val builder = ChunkBuilder.make[O2]()
      interim.foreach(o => builder ++= that.step(r2, o))
      builder.result() ++ that.onComplete(r2)
    })

object Scan:
  def apply[S <: Tuple, I, O](initial: S)(step: (TypeRegister[S], I) => Chunk[O], onComplete: TypeRegister[S] => Chunk[O]): Scan[S, I, O] =
    new Scan(initial, step, onComplete)

  def lift[I, O](f: I => O): Scan[EmptyTuple, I, O] =
    Scan(EmptyTuple)((_, i) => Chunk.single(f(i)), _ => Chunk.empty)

  def identity[I]: Scan[EmptyTuple, I, I] = lift(i => i)
