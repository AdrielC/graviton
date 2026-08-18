package graviton.streams.timeseries

final case class Windowed[A](values: Vector[A], size: Int):
  def push(value: A): Windowed[A] =
    val appended = (values :+ value).takeRight(size)
    copy(values = appended)
