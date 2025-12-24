package graviton.data

import scala.collection.immutable.Vector

/**
 * A small, dependency-free non-empty `Vector`.
 *
 * This lives in `graviton-data` so other modules can depend on basic data
 * structures without pulling in effect, schema, or backend dependencies.
 */
final case class NonEmptyVector[+A] private (head: A, tail: Vector[A]):
  def toVector: Vector[A] =
    head +: tail

  def size: Int =
    1 + tail.size

object NonEmptyVector:
  def one[A](value: A): NonEmptyVector[A] =
    NonEmptyVector(value, Vector.empty)

  def fromVector[A](values: Vector[A]): Either[String, NonEmptyVector[A]] =
    values.headOption match
      case Some(h) => Right(NonEmptyVector(h, values.drop(1)))
      case None    => Left("NonEmptyVector cannot be empty")
