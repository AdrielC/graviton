package graviton.runtime.metrics

object PrometheusTextRenderer:

  def render(snapshot: MetricsSnapshot): String =
    val counters = snapshot.counters.toList.sortBy { case (k, _) => (k.name, k.stableTags.mkString(",")) }
    val gauges   = snapshot.gauges.toList.sortBy { case (k, _) => (k.name, k.stableTags.mkString(",")) }

    val sb = new StringBuilder(4096)

    counters.foreach { case (key, value) =>
      sb.append(key.name)
      renderTagsInto(sb, key)
      sb.append(' ')
      sb.append(value.toString)
      sb.append('\n')
    }

    gauges.foreach { case (key, value) =>
      sb.append(key.name)
      renderTagsInto(sb, key)
      sb.append(' ')
      sb.append(value.toString)
      sb.append('\n')
    }

    sb.toString

  private def renderTagsInto(sb: StringBuilder, key: MetricKey): Unit =
    val tags = key.stableTags
    if tags.nonEmpty then
      sb.append('{')
      var first = true
      tags.foreach { case (k, v) =>
        if !first then sb.append(',')
        first = false
        sb.append(k)
        sb.append("=\"")
        sb.append(escape(v))
        sb.append('"')
      }
      sb.append('}')

  private def escape(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\n", "\\n")
      .replace("\"", "\\\"")
