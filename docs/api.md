# API Surface

Graviton ships **HTTP** endpoints for blob ingest/retrieve in `graviton-server`, backed by the `graviton-http` library. **gRPC** `.proto` contracts and generated stubs exist in `graviton-grpc`, but the primary runnable server today is HTTP-only — see [gRPC](./api/grpc.md) for intended shape vs current wiring.

## gRPC

`upload.proto` defines a bidirectional streaming RPC that multiplexes control, credits, and data chunks over a single stream. `blob_service.proto` and related definitions describe unary-style blob access. **Server-side exposure** of these RPCs from the same process as `server/run` is still **in progress**; prefer HTTP or embedding `BlobStore` until that work lands.

## HTTP

`graviton-http` exposes a small, demo-focused HTTP surface using zio-http: streaming blob upload/download, dashboard snapshot/stream helpers, and health/stats/schema routes. See [HTTP API](./api/http.md) for the live list.

**Authentication:** JWT/OIDC integration via `AuthMiddleware` is **planned** for production gateways; the default local server does not enforce auth.
