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

Expose the scraped data through an HTTP endpoint:

```scala
Metrics.scrape
```

Configure Prometheus to scrape the endpoint:

```yaml
scrape_configs:
  - job_name: "graviton"
    static_configs:
      - targets: ["localhost:8080"]
```

## Dashboards

Once metrics are collected, import them into Grafana and create panels based on
`graviton_put_total`, `graviton_get_latency_seconds`, and other metrics to
monitor usage and performance.
