package graviton

import zio.Chunk

/** A purely data-based, invertible arrow. It captures primitive steps and their
  * arguments without embedding executable functions, allowing the structure to
  * be inspected, serialised and reversed.
  */
sealed trait InvertibleArrow[A, B]:
  self =>
  import InvertibleArrow.*

  transparent inline def >>>[C](
      that: InvertibleArrow[B, C]
  ): InvertibleArrow[A, C] =
    Compose(self, that)

  /** Reverse this arrow. */
  def invert: InvertibleArrow[B, A] =
    this match
      case Id() => Id().asInstanceOf[InvertibleArrow[B, A]]
      case p: Primitive[?, ?] =>
        Primitive[B, A](p.inverseLabel, p.args, p.label)
          .asInstanceOf[InvertibleArrow[B, A]]
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

  /** A primitive step identified purely by metadata. */
  final case class Primitive[A, B](
      label: String,
      args: Chunk[(String, Any)] = Chunk.empty,
      inverseLabel: String
  ) extends InvertibleArrow[A, B]

  /** Composition of two arrows. */
  final case class Compose[A, B, C](
      first: InvertibleArrow[A, B],
      second: InvertibleArrow[B, C]
  ) extends InvertibleArrow[A, C]

  /** Lift a primitive step by providing its label and inverse label. */
  def lift[A, B](
      label: String,
      inverse: String,
      args: (String, Any)*
  ): InvertibleArrow[A, B] =
    Primitive(label, Chunk.fromIterable(args), inverse)
