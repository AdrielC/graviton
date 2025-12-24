package graviton.core.ranges

trait DiscreteDomain[A]:
  def next(value: A): A
  def previous(value: A): A

object DiscreteDomain:
  inline def apply[A](using domain: DiscreteDomain[A]): DiscreteDomain[A] = domain

  given DiscreteDomain[Long] = new DiscreteDomain[Long]:
    def next(value: Long): Long     = value + 1
    def previous(value: Long): Long = value - 1
  end given

  given DiscreteDomain[Int] = new DiscreteDomain[Int]:
    def next(value: Int): Int     = value + 1
    def previous(value: Int): Int = value - 1
  end given
