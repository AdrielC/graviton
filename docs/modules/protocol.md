# Protocol Stack

The protocol modules expose Graviton through shared JSON response models plus operational HTTP and gRPC transports.

| Module | Implemented surface | Status |
| --- | --- | --- |
| `protocol/graviton-shared` | Cross-platform HTTP response models plus bounded content-addressing utilities | Same contract and tests on JVM and Scala.js; native JCA/Web Crypto SHA-256 for the bounded utility |
| `protocol/graviton-http` | Operational blob lifecycle, typed streaming Scala SDK, metrics, and JWT middleware | Contract and socket tested |
| `protocol/graviton-proto` | Protobuf definitions and generated types | Generated during the build |
| `protocol/graviton-grpc` | Generated services and typed streaming client | Served by the default process on port 9090 |

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

## Transport boundaries

The published HTTP and gRPC clients target routes served by the default process and exercised over real sockets. Graviton 0.3 does not publish clients for proposed resumable or multipart routes. The stable gRPC surface deliberately models a stream as the upload session and exposes the core blob lifecycle. HTTP remains the richer transport for byte ranges, conditional requests, and server-side verification.

## Shared JVM and Scala.js contract

`graviton-shared` is a full cross-project. Common sources own HTTP response models, refined interactive byte counts, SHA-256 digest text, fixed-block analysis, content-ID rendering, and duplicate detection. JVM delegates its bounded digest utility to JCA and Scala.js delegates it to Web Crypto. Server-side `KeyBits` uses the same shared content-key syntax parser and renderer.

The 8 KiB shared analyzer remains a bounded library utility and is not the server data plane or the current file playground. The playground's `graviton-content-lab` module streams browser files and owns at most one 4 MiB block. Arbitrary-size server and SDK payloads continue through `ZStream` and scoped response bodies without full collection.
