# Production observability v1

This bundle is the portable telemetry contract for a Graviton cell. It is not a screenshot or a demo dataset. Every panel and alert reads metrics emitted by the runtime, the JVM connector, PostgreSQL pool instrumentation, S3 SDK instrumentation, Shardcake health, resumable uploads, or repair services.

## Install

1. Mount `recording-rules.yml` and `alerts.yml` into Prometheus at the paths in `prometheus.yml`.
2. Replace the example scrape target with every Graviton node in the cell.
3. Replace or remove the example `remote_write` endpoint. Keep the queue bounds.
4. Provision the Grafana datasource, dashboard provider, and `graviton-production.json`.
5. Route Alertmanager's `page`, `ticket`, and `info` receivers to an authenticated internal alert router. The receiver and privacy contract is in `alert-routing.json`.
6. For CloudWatch Logs, run the AWS OpenTelemetry Collector with `cloudwatch/otel-collector.yml` and set `GRAVITON_CLOUDWATCH_LOG_GROUP`, `GRAVITON_NODE_ID`, `GRAVITON_CELL_ID`, `GRAVITON_ENVIRONMENT`, and `AWS_REGION`.

Do not place tenant IDs, organization IDs, blob IDs, digests, filenames, principals, tokens, or payload bytes in metric labels. Cell, node, backend, operation, status, and bounded outcome labels are permitted.

## Validate

Run:

```bash
./scripts/validate-observability-bundle.py
```

When `promtool` is installed, also run:

```bash
promtool check rules deploy/observability-v1/recording-rules.yml deploy/observability-v1/alerts.yml
promtool check config deploy/observability-v1/prometheus.yml
```

The machine-readable objectives and qualification thresholds are in `slo.json`. The complete evidence classes and required gates are in `../qualification-v1/matrix.json`. Qualification evidence must identify the exact commit and deployment shape. A green dashboard alone is not production qualification.
