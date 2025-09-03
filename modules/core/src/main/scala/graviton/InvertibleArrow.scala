package graviton

import zio.prelude.*
import zio.Chunk

/** A free, invertible and introspectable arrow. It records primitive operations
  * along with their arguments and can be reversed to obtain the inverse
  * transformation.
  */
sealed trait InvertibleArrow[A, B]:
  self =>
  import InvertibleArrow.*

  transparent inline def >>>[C](
      that: InvertibleArrow[B, C]
  ): InvertibleArrow[A, C] =
    Compose(self, that)

  /** Execute this arrow on the given input. */
  def run(a: A): B =
    this match
      case Id() => a
      case p: Primitive[a1, b1] =>
        val pf = p.forward.asInstanceOf[a1 => b1]
        pf(a.asInstanceOf[a1]).asInstanceOf[B]
      case Compose(f, g) =>
        g.run(f.run(a))

  /** Reverse this arrow. */
  def invert: InvertibleArrow[B, A] =
    this match
      case Id() => Id().asInstanceOf[InvertibleArrow[B, A]]
      case p: Primitive[?, ?] =>
        Primitive[B, A](
          p.label,
          p.backward.asInstanceOf[B => A],
          p.forward.asInstanceOf[A => B],
          p.args,
          !p.inverted
        )
      case Compose(f, g) =>
        (g.invert >>> f.invert).asInstanceOf[InvertibleArrow[B, A]]

  /** List the primitive steps in order. */
  def steps: Chunk[Primitive[?, ?]] =
    this match
      case Id()               => Chunk.empty
      case p: Primitive[?, ?] => Chunk(p)
      case Compose(f, g)      => f.steps ++ g.steps

object InvertibleArrow:

  /** Identity arrow. */
  final case class Id[A]() extends InvertibleArrow[A, A]

  /** A primitive arrow that can run forward and backward. */
  final case class Primitive[A, B](
      label: String,
      forward: A => B,
      backward: B => A,
      args: Chunk[(String, Any)] = Chunk.empty,
      inverted: Boolean = false
  ) extends InvertibleArrow[A, B]

  /** Composition of two arrows. */
  final case class Compose[A, B, C](
      first: InvertibleArrow[A, B],
      second: InvertibleArrow[B, C]
  ) extends InvertibleArrow[A, C]

  /** Lift a pair of inverse functions into an arrow. */
  def lift[A, B](
      label: String,
      forward: A => B,
      backward: B => A,
      args: (String, Any)*
  ): InvertibleArrow[A, B] =
    Primitive(label, forward, backward, Chunk.fromIterable(args))
