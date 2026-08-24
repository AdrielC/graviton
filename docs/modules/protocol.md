# Protocol Stack

The protocol modules expose Graviton through shared JSON response models, HTTP, and an evolving gRPC surface.

| Module | Implemented surface | Status |
| --- | --- | --- |
| `protocol/graviton-shared` | Cross-platform health, counters, inventory, manifest, upload, and verification models | Used by JVM and Scala.js |
| `protocol/graviton-http` | Operational blob lifecycle, metrics, and JWT middleware | Contract-tested |
| `protocol/graviton-proto` | Protobuf definitions and generated types | Generated during the build |
| `protocol/graviton-grpc` | Typed clients and service implementations | Partial; not served by the default process |

## HTTP lifecycle

`HttpApi` exposes:

- `POST /api/blobs` for streaming ingestion
- `GET /api/blobs` for durable inventory
- `GET /api/blobs/:id/metadata` for persisted block layout
- `POST /api/blobs/:id/verify` for server-side streaming rehash
- `GET /api/blobs/:id` for streaming retrieval
- `HEAD /api/blobs/:id` for metadata headers
- `DELETE /api/blobs/:id` for logical manifest deletion

Uploads return `201 Created`, a stable `<algorithm>:<hex-digest>:<byte-length>` content ID, committed block counts, `Location`, and `ETag`. Invalid IDs produce a structured `400`, missing blobs produce `404`, and unexpected storage errors do not expose exception details.

The contract suite performs the complete lifecycle against the in-memory CAS. Filesystem and PostgreSQL repository suites prove that inventory and manifest inspection are derived from durable metadata. See the [HTTP API](../api/http.md) for examples.

## Authentication

`AuthMiddleware.required` validates bearer tokens through the configured `JwtVerifier`, records failed authentication, and installs `CallerContext` for downstream authorization and audit work. `AuthMiddleware.optional` validates a token when one is present.

Security-disabled mode leaves blob routes open for local development. Security-enabled mode without a configured verifier fails closed through `JwtVerifier.denyAll`; it is not an OIDC implementation by itself.

## Health and metrics

The server owns `GET /api/health` and `GET /api/stats`. The HTTP module serves `GET /metrics` from the configured `MetricsRegistry`.

Stats and Prometheus metrics are process-lifetime observations. They reset on restart. `GET /api/blobs` is the durable inventory source.

## Partial surfaces

`UploadNodeHttpClient` targets an experimental versioned multipart surface under `/api/v1/...`; corresponding stable server routes are not advertised. The gRPC clients and service implementations compile and have focused tests, but they are not yet the primary end-to-end deployment path.

The next protocol milestone is a transport-level gRPC acceptance suite and an explicit compatibility policy for public message shapes.
