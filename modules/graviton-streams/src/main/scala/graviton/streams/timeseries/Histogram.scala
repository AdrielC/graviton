package graviton.streams.timeseries

final case class Histogram(buckets: Map[Long, Long]):
  def observe(value: Long): Histogram =
    val bucket  = value / 1024
    val updated = buckets.updatedWith(bucket) {
      case Some(count) => Some(count + 1)
      case None        => Some(1L)
    }
    copy(buckets = updated)
