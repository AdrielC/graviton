# Operator Control Plane

Graviton exposes one read-only operational model for the local console and automation. It combines active storage and Shardcake probes with process memory admission, optional Redis or Valkey admission, replication repair, PostgreSQL pool pressure, S3 activity, and process-lifetime traffic counters.

The model never includes payload bytes, object names, content IDs, tenant identifiers, or the raw metric registry.

## Open the console

The local console is disabled by default. Enable it for a loopback-bound server:

```bash
GRAVITON_CONSOLE_ENABLED=true ./sbt "server/run"
```

Open `http://127.0.0.1:8081/console/operations`. The page is rendered by the server and refreshed through the bundled ZIO Blocks Datastar runtime. It shows the same typed snapshot returned by the operator API.

## Read the current snapshot

```bash
curl --fail http://127.0.0.1:8081/api/ops/v1/snapshot
```

The response has a monotonic process-local sequence and a fixed set of operational checks:

- storage readiness
- Shardcake placement, when enabled
- process-local transfer memory pressure
- distributed admission health and pressure, when enabled
- replication repair state, when active
- PostgreSQL connection pressure, when observed

`Ready` means every active check is ready. `Degraded` means work can continue but at least one active check is under pressure or repair is behind. `Unavailable` means an active dependency needed to safely accept work could not be observed. Inactive optional components do not degrade a single-node deployment.

## Stream changes

```bash
curl --no-buffer --fail http://127.0.0.1:8081/api/ops/v1/events
```

The endpoint is a `text/event-stream`. Every event is named `snapshot`, carries its sequence as the SSE ID, and uses the same ZIO Blocks JSON contract as the snapshot endpoint. A new subscriber receives the current snapshot immediately.

The server uses one supervised refresh fiber and a sliding event hub. Slow subscribers can miss intermediate observations, then recover from the next complete snapshot. They cannot apply back pressure to storage traffic or create an unbounded server queue.

## Prometheus and the control plane

Use `/api/ops/v1/snapshot` for current, bounded operator state. Use `/metrics` for alerting, rates, latency distributions, and historical dashboards. The control plane aggregates only fixed operational dimensions, so it does not turn tenant or object identity into metric labels.

When API security is enabled, both operator endpoints require a valid bearer token with `observability.read`. The loopback console remains a separate development surface and cannot be enabled with API security.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `GRAVITON_OPERATIONS_REFRESH_INTERVAL` | `5s` | Cadence of the supervised operational probe. Must be positive. |
| `GRAVITON_OPERATIONS_EVENT_CAPACITY` | `64` | Sliding in-process snapshot capacity, from 1 through 4096. |
| `GRAVITON_OPERATIONS_PRESSURE_WARNING_PERCENT` | `85` | Local, distributed, and PostgreSQL pressure threshold, from 1 through 100. |

These settings change observation and presentation only. They do not change transfer admission limits, repair policy, or backend capacity.
