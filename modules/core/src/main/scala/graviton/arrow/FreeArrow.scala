package graviton.arrow

import graviton.arrow.ArrowBundle
import graviton.arrow.BottomOf
import scala.NamedTuple

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
      case FreeArrow.Id()                              => interpreter.bundle.identity[I]
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
          interpreter.bundle.inLeft[plus.LeftOut, plus.RightOut],
          l,
        )
        val mappedRight = interpreter.bundle.compose(
          interpreter.bundle.inRight[plus.LeftOut, plus.RightOut],
          r,
        )
        interpreter.bundle.fromEither(mappedLeft)(mappedRight)
      case FreeArrow.FanIn(left, right)                =>
        interpreter.bundle.fromEither(left.compile)(right.compile)
      case inl: FreeArrow.Inl[Prim, Prod, Sum, ?, ?]   =>
        interpreter.bundle.inLeft[inl.Left, inl.Right]
      case inr: FreeArrow.Inr[Prim, Prod, Sum, ?, ?]   =>
        interpreter.bundle.inRight[inr.Left, inr.Right]

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
@@
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
    right: FreeArrow.Aux[Prim, Prod, Sum, M, O, C2],
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

  trait Interpreter[Prim[-_, +_, _ <: NamedTuple.AnyNamedTuple], Prod[+_, +_], Sum[+_, +_], =>:[-_, +_]]:
    val bundle: ArrowBundle.Aux[=>:, Prod, Sum]

    def interpret[I, O, C <: NamedTuple.AnyNamedTuple](prim: Prim[I, O, C]): I =>: O

    def zero[A, B](using bottom: BottomOf[B]): A =>: B = bundle.zero

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
