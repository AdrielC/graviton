# Protocol Stack

The protocol modules expose Graviton through shared JSON response models, HTTP, and an evolving gRPC surface.

| Module | Implemented surface | Status |
| --- | --- | --- |
| `protocol/graviton-shared` | Cross-platform health, counters, inventory, manifest, upload, and verification models | Used by JVM and Scala.js |
| `protocol/graviton-http` | Operational blob lifecycle, typed streaming Scala SDK, metrics, and JWT middleware | Contract and socket tested |
| `protocol/graviton-proto` | Protobuf definitions and generated types | Generated during the build |
| `protocol/graviton-grpc` | Typed clients and service implementations | Partial; not served by the default process |

## HTTP lifecycle

`HttpApi` exposes:

- `POST /api/v1/blobs` for streaming ingestion
- `GET /api/v1/blobs` for durable inventory
- `GET /api/v1/blobs/:id/metadata` for persisted block layout
- `POST /api/v1/blobs/:id/verify` for server-side streaming rehash
- `GET /api/v1/blobs/:id` for streaming retrieval
- `HEAD /api/v1/blobs/:id` for metadata headers
- `DELETE /api/v1/blobs/:id` for logical manifest deletion

Uploads return `201 Created`, a stable `<algorithm>:<hex-digest>:<byte-length>` content ID, committed block counts, `Location`, and `ETag`. Invalid IDs produce a structured `400`, missing blobs produce `404`, and unexpected storage errors do not expose exception details.

The contract suite performs the complete lifecycle against the in-memory CAS. Filesystem and PostgreSQL repository suites prove that inventory and manifest inspection are derived from durable metadata. See the [HTTP API](../api/http.md) for examples.

`ai.hylo.graviton.client.GravitonClient` is the supported JVM SDK for this surface. Uploads accept a stream, ZIO Blocks media type, and optional Iron-refined 1 TiB length. Downloads return a scoped stream. See the [Scala Streaming SDK](../guide/scala-sdk.md).

## Authentication

`AuthMiddleware.required` validates bearer tokens through the configured `JwtVerifier`, records failed authentication, and installs `CallerContext` for downstream authorization and audit work. `AuthMiddleware.optional` validates a token when one is present.

Security-disabled mode leaves blob routes open for local development. Security-enabled mode uses either the HS256 local proof flow or the RS256 OIDC verifier with remote HTTPS JWKS rotation. Capability policy, exact origins, TLS trust, request and byte budgets, and audit outcomes are enforced around the routes.

## Health and metrics

The server owns `GET /api/health/live`, `GET /api/health/ready`, and `GET /api/stats`. The HTTP module serves `GET /metrics` from the configured `MetricsRegistry`.

Stats and Prometheus metrics are process-lifetime observations. They reset on restart and require `observability.read` when security is enabled. `GET /api/v1/blobs` is the durable inventory source.

## Partial surfaces

`UploadNodeHttpClient` targets an experimental versioned multipart surface under `/api/v1/...`; corresponding stable server routes are not advertised. The gRPC clients and service implementations compile and have focused tests, but they are not yet the primary end-to-end deployment path.

The next protocol milestone is a runnable authenticated gRPC listener with transport-level parity acceptance. HTTP compatibility policy is documented and checked in the 0.1 release process.
