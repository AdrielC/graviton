# API Surface

Graviton exposes both gRPC and HTTP endpoints for ingesting and retrieving blobs.

## gRPC

`upload.proto` defines a bidirectional streaming RPC that multiplexes control, credits, and data chunks over a single stream. Implementations are provided in `graviton-grpc` and translate messages into runtime operations.

`blob_service.proto` surfaces simple `GetBlob` and `StatBlob` calls for clients that prefer unary operations.

`admin_service.proto` contains health and repair endpoints used by operational tooling.

## HTTP

`graviton-http` mirrors the gRPC capabilities with JSON/HTTP endpoints using zio-http. The API offers routes to start an upload, stream parts, complete an upload, fetch blobs, and inspect metadata.

Authentication is pluggable through `AuthMiddleware`, which can integrate with JWT/OIDC providers.
