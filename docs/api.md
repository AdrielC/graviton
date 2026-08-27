# API Surface

Graviton exposes its operational content-addressable store through the embedded `BlobStore` API, the CLI, and the packaged HTTP and gRPC listeners. The default server starts HTTP on port `8081` and gRPC on port `9090`.

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

`blob_service.proto` defines the operational `BlobService` lifecycle: client-streaming put, server-streaming get, stat, list, streamed inspection results, and delete. `AdminService.Health` reports storage-backed readiness. The packaged process starts both services, and `GravitonGrpcClient` exposes the lifecycle with ZIO streams. See the [gRPC API](./api/grpc.md) for limits, security behavior, and executable proof.

## Security boundary

The default local server does not enforce authentication and must remain in a trusted environment. Security-enabled mode wires RS256 OIDC/JWKS verification, capability authorization, CORS and TLS policy, streaming limits, and audit events. TLS termination and the identity-provider configuration remain operator-owned deployment boundaries.
