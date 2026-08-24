# Protocol Stack

The protocol modules expose Graviton through shared JSON models, HTTP, and an evolving gRPC surface.

| Module | Implemented surface | Status |
| --- | --- | --- |
| `protocol/graviton-shared` | Cross-platform API and dashboard models | Used by JVM and Scala.js |
| `protocol/graviton-http` | Blob lifecycle routes, metrics, dashboard snapshot/SSE, JWT middleware | Blob route contract is tested |
| `protocol/graviton-proto` | Protobuf definitions and generated types | Generated during the build |
| `protocol/graviton-grpc` | Typed clients and service shells | Partial; not the primary operational path |

## HTTP blob lifecycle

`HttpApi` exposes:

- `POST /api/blobs` for streaming ingestion
- `GET /api/blobs/:id` for streaming retrieval
- `HEAD /api/blobs/:id` for metadata without a body
- `DELETE /api/blobs/:id` for manifest deletion

Uploads return `201 Created`, a stable `<algorithm>:<hex-digest>:<byte-length>` content ID, `Location`, and `ETag`. Reads return content length, ETag, last-modified time, and immutable cache headers. Invalid IDs produce a structured `400`, missing blobs produce `404`, and unexpected storage errors do not expose exception details.

The contract suite performs a complete POST, GET, HEAD, and DELETE lifecycle against the in-memory CAS. See [HTTP API](../api/http.md) for examples.

## Authentication

`AuthMiddleware.required` validates bearer tokens through the configured `JwtVerifier`, records failed authentication, and installs `CallerContext` for downstream authorization and audit work. `AuthMiddleware.optional` validates a token when one is present.

Security-disabled mode leaves blob routes open for local development. Security-enabled mode without a configured verifier fails closed through `JwtVerifier.denyAll`; it is not an OIDC implementation by itself.

## Dashboard and metrics

The HTTP module also serves:

- `GET /api/datalake/dashboard`
- `GET /api/datalake/dashboard/stream`
- `GET /metrics`

Dashboard data is a source-backed capability snapshot. The service does not manufacture traffic or health events. Metrics are rendered from the configured `MetricsRegistry`.

The server owns `GET /api/health`, `GET /api/stats`, and `GET /api/schema`. Stats are process-lifetime ingest counters, not a durable inventory query. Schema currently returns an empty list until a catalog provider is wired.

## Partial surfaces

`UploadNodeHttpClient` targets an experimental versioned multipart surface under `/api/v1/...`; corresponding stable server routes are not advertised. The gRPC clients and service shells compile and have focused tests, but they are not yet the primary end-to-end deployment path.

The next protocol milestone is a transport-level gRPC acceptance suite and an explicit compatibility policy for public message shapes.
