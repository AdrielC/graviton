package graviton.core.ranges

trait DiscreteDomain[A]:
  def next(value: A): A
  def previous(value: A): A

given DiscreteDomain[Long] with
  def next(value: Long): Long     = value + 1
  def previous(value: Long): Long = value - 1
