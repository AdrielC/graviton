package graviton.data

import scala.collection.immutable.Set

/**
 * A small, dependency-free non-empty `Set`.
 *
 * Note: order is not stable; use `NonEmptyVector` when ordering matters.
 */
final case class NonEmptySet[A] private (head: A, tail: Set[A]):
  def toSet: Set[A] =
    tail + head

  def size: Int =
    1 + tail.size

object NonEmptySet:
  def one[A](value: A): NonEmptySet[A] =
    NonEmptySet(value, Set.empty)

  def fromSet[A](values: Set[A]): Either[String, NonEmptySet[A]] =
    values.headOption match
      case Some(h) => Right(NonEmptySet(h, values - h))
      case None    => Left("NonEmptySet cannot be empty")
