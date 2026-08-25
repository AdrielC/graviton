# API Surface

Graviton exposes its operational content-addressable store through the embedded `BlobStore` API, the CLI, and an HTTP server. gRPC contracts and clients also exist, but they are not yet served by the default process.

## Runtime API

`BlobStore` is the compatibility anchor for Scala applications. It supports streaming writes and reads, durable inventory, persisted manifest inspection, stat, verification by reading stored bytes, and logical deletion.

`Graviton.fs(root)` assembles filesystem blocks and manifests. `Graviton.inMemory` is intended for tests and short-lived processes.

## HTTP

`graviton-http` exposes the same real lifecycle:

- `POST /api/v1/blobs`
- `GET /api/v1/blobs`
- `GET /api/v1/blobs/:id/metadata`
- `POST /api/v1/blobs/:id/verify`
- `GET /api/v1/blobs/:id`
- `HEAD /api/v1/blobs/:id`
- `DELETE /api/v1/blobs/:id`

The default server uses durable filesystem storage. See the [HTTP API](./api/http.md) for response models and executable examples.

## gRPC

`upload.proto` defines a bidirectional streaming RPC that multiplexes control, credits, and data chunks. `blob_service.proto` and related definitions describe blob access. Server-side exposure from the default `server/run` process is still in progress, so use HTTP or embed `BlobStore` for operational deployments today.

## Security boundary

The default local server does not enforce authentication and must remain in a trusted environment. Security-enabled mode wires RS256 OIDC/JWKS verification, capability authorization, CORS and TLS policy, streaming limits, and audit events. TLS termination and the identity-provider configuration remain operator-owned deployment boundaries.
