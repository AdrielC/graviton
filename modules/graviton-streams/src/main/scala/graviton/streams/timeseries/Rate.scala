package graviton.streams.timeseries

final case class Rate(ema: Double, alpha: Double):
  def observe(sample: Double): Rate =
    val updated = alpha * sample + (1 - alpha) * ema
    copy(ema = updated)
