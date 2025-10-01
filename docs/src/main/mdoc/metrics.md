# Metrics

The `graviton-metrics` module instruments core binary store operations and
exposes Prometheus-compatible telemetry.

## Enabling metrics

Add the dependency and layer into your application:

```scala
libraryDependencies += "io.quasar" %% "graviton-metrics" % "<version>"
```

Wrap an existing `BinaryStore` with `MetricsBinaryStore` to collect counts and
latencies for `put`, `get`, and `delete`:

```scala
import graviton.metrics.*

val instrumented = MetricsBinaryStore(store)
```

Include the Prometheus publisher and updater layers to export metrics:

```scala
val layer = Metrics.prometheus ++ Metrics.prometheusUpdater
```

## Scraping

Expose the scraped data through an HTTP endpoint. `Metrics.scrape` returns the
Prometheus text format and can be mounted on any HTTP server:

```scala
import zio.*
import zio.http.*
import zio.metrics.connectors.prometheus.PrometheusPublisher
import graviton.metrics.Metrics

def metricsApp = Routes(
  Method.GET / "metrics" -> handler(ZIO.serviceWithZIO[PrometheusPublisher](_ => Metrics.scrape))
)

def metricsServer =
  Server.serve(metricsApp).provide(Server.default, Metrics.prometheus, Metrics.prometheusUpdater)
```

Configure Prometheus to scrape the endpoint:

```yaml
scrape_configs:
  - job_name: "graviton"
    static_configs:
      - targets: ["localhost:8080"]
```

## Metric catalog

| Metric name                        | Type     | Description                                |
|------------------------------------|----------|--------------------------------------------|
| `graviton_put_total`               | Counter  | Number of `put` operations attempted.      |
| `graviton_put_latency_seconds`     | Summary  | Latency histogram for `put` requests.      |
| `graviton_get_total`               | Counter  | Number of `get` requests.                  |
| `graviton_get_latency_seconds`     | Summary  | Latency histogram for `get` requests.      |
| `graviton_delete_total`            | Counter  | Number of `delete` calls executed.         |
| `graviton_delete_latency_seconds`  | Summary  | Latency histogram for `delete` operations. |

Combine counters with histogram quantiles to build dashboards and SLOs.

## Dashboards and alerts

Grafana panels typically chart `rate(graviton_put_total[5m])` for ingest
volume, `histogram_quantile(0.95, sum(rate(graviton_put_latency_seconds_bucket[5m])) by (le))`
for latency, and `rate(graviton_delete_total[5m])` to detect unexpected
purges. Wire these queries into alert rules so on-call responders receive
notifications when tail latencies or error spikes exceed agreed thresholds.

## Tuning the update interval

`Metrics.prometheusUpdater` publishes updates every five seconds by default. If
you want slower scrapes, replace it with a custom layer:

```scala
import zio.metrics.connectors.prometheus.{PrometheusPublisher, prometheusLayer, publisherLayer}
import zio.metrics.connectors.MetricsConfig
import zio.ZLayer
import graviton.metrics.Metrics
import java.time.Duration

val customPrometheus =
  ZLayer.succeed(MetricsConfig(Duration.ofSeconds(30))) >>>
    (Metrics.prometheus ++ prometheusLayer)
```

Provide `customPrometheus` instead of `Metrics.prometheus` and
`Metrics.prometheusUpdater` when starting your application.
