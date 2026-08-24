# Operational Evidence Dashboard

This page explains the optional dashboard payload and its evidence policy.

## What the endpoint returns

`GET /api/datalake/dashboard` returns:

- a capability snapshot;
- the `zio-schema` AST describing that snapshot;
- an accessor graph used by the Scala.js schema explorer.

The initial capability snapshot is repository reference data. It cites implementation files, tests, and validation commands. It is not live production telemetry.

`GET /api/datalake/dashboard/stream` is an SSE channel. It emits only values supplied through `DatalakeDashboardService.publish`. Graviton does not generate random throughput, replica, or ingest events.

## Evidence rules

| Label | Meaning |
| --- | --- |
| Operational | A public path has implementation and end-to-end coverage |
| Integration-tested | The path runs against its external dependency in gated CI |
| Partial | Some components work, but the named end-to-end path is incomplete |
| Planned | Design or ports exist without an operational implementation |

Performance numbers are intentionally absent until a reproducible benchmark records hardware, dataset, configuration, command, and result provenance.

## Current proof paths

| Capability | Evidence |
| --- | --- |
| Core CAS round-trip | `CasRoundTripSpec` |
| Restart-safe filesystem mode | `FsBlobManifestRepoSpec` and `GravitonSpec` |
| HTTP lifecycle | `HttpApiSpec` |
| RocksDB key-value adapter | `RocksKeyValueStoreSpec` |
| PostgreSQL and MinIO paths | Container-gated CI jobs |
| Public site | `buildDocsAssets`, mdoc check, and VitePress build jobs |

## Schema-driven UI

The Scala.js dashboard can render and edit the reference structure from schema-derived accessors. Browser-side edits remain local unless an application explicitly publishes them through a configured server integration.

## Verify the repository

```bash
TESTCONTAINERS=0 ./sbt scalafmtAll test
./sbt docs/mdoc checkDocSnippets
./sbt buildDocsAssets
npm ci --prefix docs
npm run docs:build --prefix docs
```

For the smallest operational demonstration:

```bash
./scripts/demo-local.sh
```
