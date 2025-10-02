package graviton.ranges

import cats.kernel.Order
import cats.kernel.instances.long.given

/** A totally ordered, discrete domain with safe successor/precedent operations. */
trait DiscreteDomain[A]:
  def order: Order[A]

  /** Next greater element or None at the upper boundary. */
  def next(a: A): Option[A]

  /** Previous smaller element or None at the lower boundary. */
  def prev(a: A): Option[A]

  /** Optional global minimum. */
  def minValue: Option[A] = None

  /** Optional global maximum. */
  def maxValue: Option[A] = None

  /** True if `y` immediately follows `x` within the domain. */
  inline def adjacent(x: A, y: A): Boolean = next(x).contains(y)

object DiscreteDomain:
  /** Safe discrete domain for `Long` values that prevents overflow. */
  given longDomain: DiscreteDomain[Long] with
    override val order: Order[Long] = Order[Long]

    override def next(a: Long): Option[Long] =
      if a == Long.MaxValue then None else Some(a + 1L)

    override def prev(a: Long): Option[Long] =
      if a == Long.MinValue then None else Some(a - 1L)

    override val minValue: Option[Long] = Some(Long.MinValue)
    override val maxValue: Option[Long] = Some(Long.MaxValue)
