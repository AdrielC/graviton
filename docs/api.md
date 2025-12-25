# API Surface

Graviton exposes both gRPC and HTTP endpoints for ingesting and retrieving blobs.

## gRPC

`upload.proto` defines a bidirectional streaming RPC that multiplexes control, credits, and data chunks over a single stream. Implementations are provided in `graviton-grpc` and translate messages into runtime operations.

`blob_service.proto` surfaces simple `GetBlob` and `StatBlob` calls for clients that prefer unary operations.

`admin_service.proto` contains health and repair endpoints used by operational tooling.

## HTTP

`graviton-http` exposes a small, demo-focused HTTP surface using zio-http. It currently supports streaming blob upload/download plus dashboard snapshot/stream endpoints; see [`api/http`](./api/http.md).

Authentication is pluggable through `AuthMiddleware`, which can integrate with JWT/OIDC providers.
