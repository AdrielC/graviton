# Shardcake Upload Locality

`graviton-shardcake` is the optional multi-node upload-locality runtime. It uses Shardcake 2.8.1 to give each typed `(tenant, upload session)` key one owner while keeping the data plane on ZIO Streams.

## What it does

1. The public HTTP node receives `X-Graviton-Tenant-Id` and `X-Graviton-Upload-Session-Id`.
2. A bounded Shardcake control message resolves that session to an owner.
3. If the current node owns it, the body enters CAS locally. Otherwise, zio-http sends the original stream directly to the owner once.
4. The owner performs the normal PDF-aware or generic CAS ingest and persists blocks and the manifest in the configured shared stores.

The upload body is never serialized through Shardcake, PostgreSQL, gRPC, or a node-local queue. Only control envelopes and the bounded result envelope use ZIO Blocks MessagePack. The sole raw byte-array signatures are isolated behind Shardcake's required `Serialization` ABI; Graviton's API remains `Chunk` and `ZStream` based.

## Locality and failure semantics

- The same tenant and session key resolves to the same live owner.
- Different sessions distribute across the configured shard set.
- Node-local hot state is bounded and reconstructable. It stores counters and phase only, never upload bytes.
- When a node drains, the manager reassigns its shards and later requests resolve to a surviving node.
- Server readiness fails until the local node has at least one assigned shard, so traffic does not arrive before placement is operational.
- An in-flight stream is not replayed after a connection failure. The caller decides whether its source is repeatable and whether to retry with the same session ID.
- CAS content identity remains the correctness boundary. Locality is an optimization, not an alternate storage format.

The PostgreSQL adapter persists pod membership and shard assignments. Independent adapters observe changes through a bounded polling interval. A process-lifetime PostgreSQL advisory lock permits one logical manager and fails closed if a second manager starts.

## ZIO service boundaries

The runtime stays split across orthogonal services:

| Service | Responsibility |
| --- | --- |
| `UploadPlacement` | Resolve a typed session and expose current assignments |
| `UploadNodeTransport` | Send one non-replayable stream directly to a selected owner |
| `UploadNodeIngest` | Perform owner-local CAS ingest |
| `UploadHotState` | Keep bounded reconstructable acceleration state |
| `UploadSessionContext` | Scope the current session through a `FiberRef` |
| `LocalityAwareUpload` | Compose placement with local or remote execution |
| `ShardcakeHealth` | Probe placement, retain the last successful check, drive readiness, and expose a safe operator snapshot |

Each service has a testable port. Shardcake, zio-http, PostgreSQL, PDF-aware ingest, and metrics remain adapters around those ports.

Health checks distinguish startup, healthy, rebalancing, unassigned, and unavailable states. A partial assignment set remains ready when the local node owns shards, so a rolling rebalance does not eject a node that can still route uploads. Placement calls use the configured Shardcake send timeout. Status transitions are logged with `component`, `operation`, and `node_id` annotations; tenant and upload session values appear only in upload log context and never in metric labels.

The adapter records health outcomes, probe duration, readiness, cluster and local assignment counts, observed nodes, and tracked hot-state entries through the shared ZIO Metrics-backed registry. The local console's Runtime view reads the same health service and process counters that feed readiness and Prometheus.

## Security boundaries

- One Iron-refined 32 to 256 character token authenticates manager GraphQL, node gRPC control traffic, and direct owner HTTP traffic.
- gRPC and MessagePack control payloads are capped at 64 KiB.
- Public authenticated uploads bind the tenant header to the caller's JWT organization UUID before pulling the body.
- Browser preflight explicitly allows the two locality headers.
- Internal authentication is checked before the request body stream is consumed.
- Shardcake 2.8.1 uses plaintext internode gRPC and Graviton's direct owner URL is HTTP. Keep both on a private network and use a service mesh or equivalent mTLS tunnel when transport encryption is required.

Use a secret manager or mounted secret. Do not place the internal token in source control or command history.

## Evidence

```bash
./sbt \
  'runtime/testOnly graviton.runtime.upload.UploadRuntimeSpec' \
  'shardcakeIntegration/testOnly graviton.integration.shardcake.ShardcakeIntegrationSpec' \
  'shardcakeIntegration/testOnly graviton.integration.shardcake.ShardcakeHealthSpec' \
  'shardcakeIntegration/testOnly graviton.integration.shardcake.ShardcakeReassignmentSpec' \
  'shardcakeIntegration/testOnly graviton.integration.shardcake.PgShardcakeStorageSpec'
```

The suite covers FiberRef lifetime, single-pull and interruption-safe routing, route metrics, bounded control serialization, authenticated real-socket 16 MiB streaming, pre-body authentication rejection, PDF-aware reusable CAS blocks, two-node stickiness and reassignment, durable PostgreSQL state, cross-adapter change propagation, and singleton-manager lease handoff. The underlying CAS suite separately proves that stopped persistence backpressures the upload source.

See [Configuration Reference](../guide/configuration-reference.md) and [Deployment](../ops/deployment.md) for the runnable topology.
