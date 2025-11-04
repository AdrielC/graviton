# Protocol Stack

The protocol modules expose Graviton’s functionality over HTTP, gRPC, and shared JSON models. They sit on top of the runtime ports and can be consumed by the Scala.js frontend, CLI clients, or external integrations.

| Module | Description | Notes |
| --- | --- | --- |
| `protocol/graviton-shared` | Cross-platform data models (`ApiModels`) and a minimal `HttpClient` interface used by Scala.js. | JSON codecs derive from zio-json so they can compile to both JVM and JS targets. |
| `protocol/graviton-proto` | Protobuf contracts for the gRPC services. | The `.proto` files live under `src/main/protobuf/graviton`; code generation is handled via sbt. |
| `protocol/graviton-grpc` | zio-grpc clients and service shells for binary ingest and admin endpoints. | Includes `UploadNodeGrpcClient` for session orchestration and placeholders that delegate to `BlobStore`. |
| `protocol/graviton-http` | zio-http clients, routes, and JSON codecs for REST-style access. | Provides `UploadNodeHttpClient` for multipart lifecycles; server handlers still return stub responses. |

## Shared models (`graviton-shared`)

- `ApiModels` contains the types exchanged between the frontend and HTTP API: blob metadata, manifests, health responses, and upload requests.
- `HttpClient` defines the minimal HTTP surface needed by Scala.js. `BrowserHttpClient` in the frontend implements it using the Fetch API.

## gRPC services (`graviton-grpc`)

- `UploadNodeGrpcClient` wraps a zio-grpc generated `UploadServiceClient`, framing register/stream/complete calls and validating acks.
- `BlobServiceImpl` and `UploadServiceImpl` still delegate to a `BlobStore`; they will pick up the scoped upload model in a subsequent change.
- `AdminServiceImpl` exposes a simple `health` call returning a static status string.
- Generated service interfaces from `graviton-proto` will wrap these implementations once the wiring is complete in `graviton-server`.

## HTTP surface (`graviton-http`)

- `UploadNodeHttpClient` manages session registration, part uploads, and finalisation against upload nodes using zio-http.
- `HttpApi` constructs a zio-http `Handler` graph. At the moment it replies with `Response.text("ok")`; route composition and request decoding are still TODO.
- `AuthMiddleware.optional` is a placeholder that simply returns the wrapped handler. Replace it with token validation once authn/authz is designed.
- `JsonCodecs` demonstrates how zio-schema will be leveraged for automatic schema derivation and request validation.

## Protocol roadmap

1. Finalise the `.proto` definitions and generate strongly-typed Scala services with zio-grpc.
2. Replace HTTP stubs with proper routing, JSON encoders/decoders, and error handling.
3. Add acceptance tests that exercise both HTTP and gRPC transports against in-memory backends.
4. Document versioning and backwards compatibility guarantees for public API shapes.
