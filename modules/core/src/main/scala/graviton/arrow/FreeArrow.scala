package graviton.arrow

import graviton.arrow.ArrowBundle
import graviton.arrow.BottomOf
import scala.NamedTuple
import zio.{Chunk, ChunkBuilder}

import scala.quoted.*
import scala.NamedTuple.AnyNamedTuple

/**
 * Free arrow with extensible primitive leaves and explicit capability tracking
 * at the type level. The structure is parameterised by the primitive algebra
 * (\`Prim\`), the product type constructor \`Prod\`, and the sum type constructor
 * \`Sum\`.
 */
sealed trait FreeArrow[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], -I, +O]:
  self =>

  type Caps <: NamedTuple.AnyNamedTuple

  def compile[=>:[-_, +_]](using interpreter: FreeArrow.Interpreter[Prim, Prod, Sum, =>:]): I =>: O =
    this match
      case FreeArrow.Id()                              => interpreter.bundle.liftArrow(identity)
      case FreeArrow.Pure(f)                           => interpreter.bundle.liftArrow[I, O](f)
      case zero: FreeArrow.Zero[Prim, Prod, Sum, ?, ?] =>
        interpreter.zero[I, O](using zero.bottom.asInstanceOf[BottomOf[O]])
      case FreeArrow.Embed(prim)                       => interpreter.interpret(prim)
      case FreeArrow.Iso(forward, _)                   => forward.compile
      case FreeArrow.Compose(left, right)              =>
        val l = left.compile
        val r = right.compile
        interpreter.bundle.compose(r, l)
      case FreeArrow.Split(left, right)                =>
        val l = left.compile
        val r = right.compile
        interpreter.bundle.toBoth(l)(r)
      case FreeArrow.Parallel(left, right)             =>
        interpreter.bundle.parallel(left.compile, right.compile)
      case plus @ FreeArrow.Plus(left, right)          =>
        val l           = left.compile
        val r           = right.compile
        val mappedLeft  = interpreter.bundle.compose(
          interpreter.inL[plus.LeftOut, plus.RightOut],
          l,
        )
        val mappedRight = interpreter.bundle.compose(
          interpreter.inR[plus.LeftOut, plus.RightOut],
          r,
        )
        interpreter.bundle.fromEither(mappedLeft)(mappedRight)
      case FreeArrow.FanIn(left, right)                =>
        interpreter.bundle.fromEither(left.compile)(right.compile)
      case inl: FreeArrow.Inl[Prim, Prod, Sum, ?, ?]   =>
        interpreter.inL[inl.Left, inl.Right]
      case inr: FreeArrow.Inr[Prim, Prod, Sum, ?, ?]   =>
        interpreter.inR[inr.Left, inr.Right]

object FreeArrow:

  type Aux[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], -I, +O, C <: NamedTuple.AnyNamedTuple] =
    FreeArrow[Prim, Prod, Sum, I, O] { type Caps = C }

  type CapUnion[A <: NamedTuple.AnyNamedTuple, B <: NamedTuple.AnyNamedTuple] = NamedTuple.Concat[A, B]

  final case class Id[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], A]() extends FreeArrow[Prim, Prod, Sum, A, A]:
    type Caps = NamedTuple.Empty

  final case class Pure[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], I, O](f: I => O)
      extends FreeArrow[Prim, Prod, Sum, I, O]:
    type Caps = NamedTuple.Empty

  final case class Zero[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], I, O](bottom: BottomOf[O])
      extends FreeArrow[Prim, Prod, Sum, I, O]:
    type Caps = NamedTuple.Empty

  final case class Embed[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], I, O, C <: NamedTuple.AnyNamedTuple](
    prim: Prim[I, O, C]
  )
      extends FreeArrow[Prim, Prod, Sum, I, O]:
    type Caps = C

  final case class Iso[
    Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple],
    Prod[+_, +_],
    Sum[+_, +_],
    I,
    O,
    C1 <: NamedTuple.AnyNamedTuple,
    C2 <: NamedTuple.AnyNamedTuple,
  ](
    forward: FreeArrow.Aux[Prim, Prod, Sum, I, O, C1],
    backward: FreeArrow.Aux[Prim, Prod, Sum, O, I, C2],
  ) extends FreeArrow[Prim, Prod, Sum, I, O]:
    type Caps = CapUnion[C1, C2]

  final case class Compose[
    Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple],
    Prod[+_, +_],
    Sum[+_, +_],
    I,
    M,
    O,
    C1 <: NamedTuple.AnyNamedTuple,
    C2 <: NamedTuple.AnyNamedTuple,
  ](
    left: FreeArrow.Aux[Prim, Prod, Sum, I, M, C1],
    right: FreeArrow.Aux[Prim, Prod, Sum, M, O, C2]
  ) extends FreeArrow[Prim, Prod, Sum, I, O]:
    type Caps = CapUnion[C1, C2]

  final case class Split[
    Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple],
    Prod[+_, +_],
    Sum[+_, +_],
    I,
    O1,
    O2,
    C1 <: NamedTuple.AnyNamedTuple,
    C2 <: NamedTuple.AnyNamedTuple,
  ](
    left: FreeArrow.Aux[Prim, Prod, Sum, I, O1, C1],
    right: FreeArrow.Aux[Prim, Prod, Sum, I, O2, C2],
  ) extends FreeArrow[Prim, Prod, Sum, I, Prod[O1, O2]]:
    type Caps = CapUnion[C1, C2]

  final case class Parallel[
    Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple],
    Prod[+_, +_],
    Sum[+_, +_],
    I1,
    I2,
    O1,
    O2,
    C1 <: NamedTuple.AnyNamedTuple,
    C2 <: NamedTuple.AnyNamedTuple,
  ](
    left: FreeArrow.Aux[Prim, Prod, Sum, I1, O1, C1],
    right: FreeArrow.Aux[Prim, Prod, Sum, I2, O2, C2],
  ) extends FreeArrow[Prim, Prod, Sum, Prod[I1, I2], Prod[O1, O2]]:
    type Caps = CapUnion[C1, C2]

  final case class Plus[
    Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple],
    Prod[+_, +_],
    Sum[+_, +_],
    I1,
    I2,
    O1,
    O2,
    C1 <: NamedTuple.AnyNamedTuple,
    C2 <: NamedTuple.AnyNamedTuple,
  ](
    left: FreeArrow.Aux[Prim, Prod, Sum, I1, O1, C1],
    right: FreeArrow.Aux[Prim, Prod, Sum, I2, O2, C2],
  ) extends FreeArrow[Prim, Prod, Sum, Sum[I1, I2], Sum[O1, O2]]:
    type Caps     = CapUnion[C1, C2]
    type LeftOut  = O1
    type RightOut = O2

  final case class FanIn[
    Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple],
    Prod[+_, +_],
    Sum[+_, +_],
    I1,
    I2,
    O,
    C1 <: NamedTuple.AnyNamedTuple,
    C2 <: NamedTuple.AnyNamedTuple,
  ](
    left: FreeArrow.Aux[Prim, Prod, Sum, I1, O, C1],
    right: FreeArrow.Aux[Prim, Prod, Sum, I2, O, C2],
  ) extends FreeArrow[Prim, Prod, Sum, Sum[I1, I2], O]:
    type Caps = CapUnion[C1, C2]

  final case class Inl[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], A, B]()
      extends FreeArrow[Prim, Prod, Sum, A, Sum[A, B]]:
    type Caps  = NamedTuple.Empty
    type Left  = A
    type Right = B

  final case class Inr[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], A, B]()
      extends FreeArrow[Prim, Prod, Sum, B, Sum[A, B]]:
    type Caps  = NamedTuple.Empty
    type Left  = A
    type Right = B

  trait Interpreter[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], P[+_, +_], S[+_, +_], =>:[-_, +_]](
    using val bundle: ArrowBundle[=>:] { type :*:[+l, +r] = P[l, r]; type :+:[+l, +r] = S[l, r] }
  ):

    import bundle.{:+:, toLeft, toRight}    

    def inL[A, B]: A =>: (S[A, B]) = bundle.toLeft[A]

    def inR[A, B]: B =>: (S[A, B]) = bundle.toRight[B]

    def interpret[I, O, C <: NamedTuple.AnyNamedTuple](prim: Prim[I, O, C]): I =>: O

    inline def zero[A, B](using BottomOf[B]): A =>: B = bundle.zero

    def lift[A, B](f: A => B): A =>: B = bundle.liftArrow(f)

  inline def id[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], A]: FreeArrow.Aux[
    Prim,
    Prod,
    Sum,
    A,
    A,
    NamedTuple.Empty,
  ] =
    Id()

  inline def pure[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], I, O](
    f: I => O
  ): FreeArrow.Aux[Prim, Prod, Sum, I, O, NamedTuple.Empty] =
    Pure(f)

  inline def zero[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], I, O](
    using bottom: BottomOf[O]
  ): FreeArrow.Aux[Prim, Prod, Sum, I, O, NamedTuple.Empty] =
    Zero(bottom)

  inline def embed[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], I, O, C <: NamedTuple.AnyNamedTuple](
    prim: Prim[I, O, C]
  ): FreeArrow.Aux[Prim, Prod, Sum, I, O, C] =
    Embed(prim)

  inline def iso[
    Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple],
    Prod[+_, +_],
    Sum[+_, +_],
    I,
    O,
    C1 <: NamedTuple.AnyNamedTuple,
    C2 <: NamedTuple.AnyNamedTuple,
  ](
    forward: FreeArrow.Aux[Prim, Prod, Sum, I, O, C1],
    backward: FreeArrow.Aux[Prim, Prod, Sum, O, I, C2],
  ): FreeArrow.Aux[Prim, Prod, Sum, I, O, CapUnion[C1, C2]] =
    Iso(forward, backward)

  inline def inl[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], A, B]: FreeArrow.Aux[
    Prim,
    Prod,
    Sum,
    A,
    Sum[A, B],
    NamedTuple.Empty,
  ] =
    Inl()

  inline def inr[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], A, B]: FreeArrow.Aux[
    Prim,
    Prod,
    Sum,
    B,
    Sum[A, B],
    NamedTuple.Empty,
  ] =
    Inr()

  extension [Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], I, O, C <: NamedTuple.AnyNamedTuple](
    self: FreeArrow.Aux[Prim, Prod, Sum, I, O, C]
  )
    transparent inline def >>>[O2, C2 <: NamedTuple.AnyNamedTuple](
      inline that: FreeArrow.Aux[Prim, Prod, Sum, O, O2, C2]
    ): FreeArrow.Aux[Prim, Prod, Sum, I, O2, CapUnion[C, C2]] =
      Compose(self, that)

    transparent inline def &&&[O2, C2 <: NamedTuple.AnyNamedTuple](
      inline that: FreeArrow.Aux[Prim, Prod, Sum, I, O2, C2]
    ): FreeArrow.Aux[Prim, Prod, Sum, I, Prod[O, O2], CapUnion[C, C2]] =
      Split(self, that)

    transparent inline def ***[I2, O2, C2 <: NamedTuple.AnyNamedTuple](
      inline that: FreeArrow.Aux[Prim, Prod, Sum, I2, O2, C2]
    ): FreeArrow.Aux[Prim, Prod, Sum, Prod[I, I2], Prod[O, O2], CapUnion[C, C2]] =
      Parallel(self, that)

    transparent inline def +++[I2, O2, C2 <: NamedTuple.AnyNamedTuple](
      inline that: FreeArrow.Aux[Prim, Prod, Sum, I2, O2, C2]
    ): FreeArrow.Aux[Prim, Prod, Sum, Sum[I, I2], Sum[O, O2], CapUnion[C, C2]] =
      Plus(self, that)

    transparent inline def |||[I2, C2 <: NamedTuple.AnyNamedTuple](
      inline that: FreeArrow.Aux[Prim, Prod, Sum, I2, O, C2]
    ): FreeArrow.Aux[Prim, Prod, Sum, Sum[I, I2], O, CapUnion[C, C2]] =
      FanIn(self, that)

    transparent inline def map[O2](inline f: O => O2): FreeArrow.Aux[Prim, Prod, Sum, I, O2, C] =
      (self >>> pure(f)).asInstanceOf[FreeArrow.Aux[Prim, Prod, Sum, I, O2, C]]

    transparent inline def contramap[I2](inline g: I2 => I): FreeArrow.Aux[Prim, Prod, Sum, I2, O, C] =
      (pure(g) >>> self).asInstanceOf[FreeArrow.Aux[Prim, Prod, Sum, I2, O, C]]

    transparent inline def dimap[I2, O2](inline g: I2 => I)(inline f: O => O2): FreeArrow.Aux[Prim, Prod, Sum, I2, O2, C] =
      (pure(g) >>> self >>> pure(f)).asInstanceOf[FreeArrow.Aux[Prim, Prod, Sum, I2, O2, C]]

    transparent inline def first[C2]: FreeArrow.Aux[Prim, Prod, Sum, Prod[I, C2], Prod[O, C2], C] =
      (self *** FreeArrow.id[Prim, Prod, Sum, C2]).asInstanceOf[FreeArrow.Aux[Prim, Prod, Sum, Prod[I, C2], Prod[O, C2], C]]

    transparent inline def second[C2]: FreeArrow.Aux[Prim, Prod, Sum, Prod[C2, I], Prod[C2, O], C] =
      (FreeArrow.id[Prim, Prod, Sum, C2] *** self).asInstanceOf[FreeArrow.Aux[Prim, Prod, Sum, Prod[C2, I], Prod[C2, O], C]]

    transparent inline def left[C2]: FreeArrow.Aux[Prim, Prod, Sum, Sum[I, C2], Sum[O, C2], C] =
      (self +++ FreeArrow.id[Prim, Prod, Sum, C2]).asInstanceOf[FreeArrow.Aux[Prim, Prod, Sum, Sum[I, C2], Sum[O, C2], C]]

    transparent inline def right[C2]: FreeArrow.Aux[Prim, Prod, Sum, Sum[C2, I], Sum[C2, O], C] =
      (FreeArrow.id[Prim, Prod, Sum, C2] +++ self).asInstanceOf[FreeArrow.Aux[Prim, Prod, Sum, Sum[C2, I], Sum[C2, O], C]]

  extension [Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], I, O](
    self: FreeArrow.Aux[Prim, Prod, Sum, Sum[I, O], Sum[I, O], NamedTuple.Empty]
  ) inline def widen: self.type = self




end FreeArrow



final case class Frame(bytes: Chunk[Byte], ordinal: Long, rolling: Long, flagged: Boolean)

object Frame:
  def apply(bytes: Chunk[Byte], ordinal: Long, rolling: Long, flagged: Boolean): Frame = Frame(bytes, ordinal, rolling, flagged)

  given Conversion[Chunk[Byte], Frame] = Frame(_, 0, 0, false)
  given Conversion[Frame, Chunk[Byte]] = _.bytes

final case class ChunkAccumulator(buffer: Chunk[Byte]):
	def ++(chunk: Chunk[Byte]): ChunkAccumulator = ChunkAccumulator(buffer ++ chunk)

	def emit(frameBytes: Int): (ChunkAccumulator, Chunk[Chunk[Byte]]) =
		var remaining = buffer
		val builder   = ChunkBuilder.make[Chunk[Byte]]()
		while remaining.length >= frameBytes do
			val (emitChunk, tail) = remaining.splitAt(frameBytes)
			builder += emitChunk
			remaining = tail
		(ChunkAccumulator(remaining), builder.result())

object ChunkAccumulator:
	val empty: ChunkAccumulator = ChunkAccumulator(Chunk.empty)

end ChunkAccumulator


final case class ChunkerHandle[Label <: String & Singleton, Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple]](
  slot: StateSlot[Label, ChunkAccumulator],
  arrow: FreeArrow.Aux[Prim, Tuple2, Either, Chunk[Byte], Chunk[Chunk[Byte]], Field[Label, ChunkAccumulator]],
)

object ChunkerHandle:

  inline def make[l <: String & Singleton, Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple]](
    inline frameBytes: Int,
    inline prim: (StateSlot[l | "chunker", ChunkAccumulator], Int) => Prim[Chunk[Byte], Chunk[Chunk[Byte]], Field[l | "chunker", ChunkAccumulator]],
  )(
    // using inline t: Type[l], inline p: Type[Prim[Chunk[Byte], Chunk[Chunk[Byte]], Field[l | "chunker", ChunkAccumulator]]]
  ): ChunkerHandle[l | "chunker", Prim] = 
    ${  chunkerImpl( 'frameBytes, '{prim}) }

  private def chunkerImpl[
    l <: String & Singleton, 
    Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple],
  ](
    frameBytesExpr: Expr[Int], 
    primExpr: Expr[(StateSlot[l | "chunker", ChunkAccumulator], Int) => Prim[Chunk[Byte], Chunk[Chunk[Byte]], Field[l | "chunker", ChunkAccumulator]]]
  )(using quotes: Quotes, t: Type[l], p: Type[Prim]): Expr[ChunkerHandle[l | "chunker", Prim]] =
    import quotes.reflect.*
    type Label = l | "chunker"
    val labelValue = StateNaming.derive[l, "chunker"]
    val l = StringConstant(labelValue)
    val labelType  = TypeRepr.of[Label]
    labelType.asType match
      case '[l] =>
        
        '{ 
          val slot: StateSlot[Label, ChunkAccumulator] = StateSlot[Label](
															StateNaming.derive[Label, "chunker"]
														)(ChunkAccumulator.empty)

          new ChunkerHandle[Label, Prim](
            slot, 
            FreeArrow.embed[Prim, Tuple2, Either, Chunk[Byte], Chunk[Chunk[Byte]], Field[Label, ChunkAccumulator]](
              $primExpr(
                slot, $frameBytesExpr)
            )
          )
        }

sealed trait ChunkerPrim[-I, +O, C <: NamedTuple.AnyNamedTuple]
object ChunkerPrim:
  final case class FixedChunker[Label <: String & Singleton](
    slot: StateSlot[Label, ChunkAccumulator],
    frameBytes: Int,
  ) extends ChunkerPrim[Chunk[Byte], Chunk[Chunk[Byte]], Field[Label, ChunkAccumulator]]


transparent inline given functionBundle: ArrowBundle.Aux[Function, Tuple2, Either] = new ArrowBundle[Function] {
  final type :*:[+l, +r] = Tuple2[l, r]
  final type :+:[+l, +r] = Either[l, r]

  def compose[A, B, C](bc: B => C, ab: A => B): A => C = bc.compose(ab)

  def fromFirst[A]: ((A, Any)) => A = _._1

  def fromSecond[B]: ((Any, B)) => B = _._2

  def toBoth[A, B, C](a2b: A => B)(a2c: A => C): A => (B, C) =
    a => (a2b(a), a2c(a))

  def parallel[A, B, C, D](left: A => B, right: C => D): ((A, C)) => (B, D) = { case (a, c) => (left(a), right(c)) }

  def toLeft[A]: A => Either[A, Nothing] = Left(_)

  def toRight[B]: B => Either[Nothing, B] = Right(_)

  def fromEither[A, B, C](left: => A => C)(right: => B => C): Either[A, B] => C = {
    case Left(a)  => left(a)
    case Right(b) => right(b)
  }

  override def liftArrow[A, B](f: A => B): A => B = f
}

given [Prim[-i, +o, _ <: NamedTuple.AnyNamedTuple] <: (i => o)] => FreeArrow.Interpreter[Prim, Tuple2, Either, Function] = 
  new FreeArrow.Interpreter[Prim, Tuple2, Either, Function]:
    final type :*:[+l, +r] = Tuple2[l, r]
    final type :+:[+l, +r] = Either[l, r]
    def interpret[I, O, C <: AnyNamedTuple](prim: Prim[I, O, C]): I => O = 
      prim