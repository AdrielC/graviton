# Protocol Stack

The protocol modules expose Graviton’s functionality over HTTP, gRPC, and shared JSON models. They sit on top of the runtime ports and can be consumed by the Scala.js frontend, CLI clients, or external integrations.

| Module | Description | Notes |
| --- | --- | --- |
| `protocol/graviton-shared` | Cross-platform data models (`ApiModels`) and a minimal `HttpClient` interface used by Scala.js. | JSON codecs derive from zio-json so they can compile to both JVM and JS targets. |
| `protocol/graviton-proto` | Protobuf contracts for the gRPC services. | The `.proto` files live under `src/main/protobuf/graviton`; sbt-scalaPB + zio-grpc generate `io.graviton.blobstore.v1` stubs. |
| `protocol/graviton-grpc` | zio-grpc clients and service shells for blob ingest and catalog access. | Ships `GravitonUploadGatewayClientZIO`, `GravitonCatalogClientZIO`, and placeholder runtime bindings. |
| `protocol/graviton-http` | zio-http clients, routes, and JSON codecs for REST-style access. | Provides `GravitonUploadHttpClient` for multipart lifecycles; server handlers still return stub responses. |

## Shared models (`graviton-shared`)

- `ApiModels` contains the types exchanged between the frontend and HTTP API: blob metadata, manifests, health responses, and upload requests.
- `HttpClient` defines the minimal HTTP surface needed by Scala.js. `BrowserHttpClient` in the frontend implements it using the Fetch API.

## gRPC services (`graviton-grpc`)

- `GravitonUploadGatewayClientZIO` wraps the generated `UploadGateway`/`UploadService` stubs, enforces ack ordering, detects TTL expiry, and exposes classic multipart helpers.
- `GravitonCatalogClientZIO` layers ergonomic ZIO APIs over the Catalog service (search, dedupe, export, subscribe).
- Service shells (`BlobServiceImpl`, `UploadServiceImpl`, `AdminServiceImpl`) still delegate to `BlobStore`; wiring to the new upload model remains pending.
- Code generation is managed via sbt-scalaPB with zio-grpc targets baked into the `graviton-proto` project.

## HTTP surface (`graviton-http`)

- `GravitonUploadHttpClient` wires the REST counterpart of `UploadService`, streaming multipart bodies over zio-http and keeping blob hashes aligned with the frames path.
- `HttpApi` constructs a zio-http `Handler` graph. At the moment it replies with `Response.text("ok")`; route composition and request decoding are still TODO.
- `AuthMiddleware.optional` is a placeholder that simply returns the wrapped handler. Replace it with token validation once authn/authz is designed.
- `JsonCodecs` demonstrates how zio-schema will be leveraged for automatic schema derivation and request validation.

## Protocol roadmap

1. Finalise the `.proto` definitions and generate strongly-typed Scala services with zio-grpc.
2. Replace HTTP stubs with proper routing, JSON encoders/decoders, and error handling.
3. Add acceptance tests that exercise both HTTP and gRPC transports against in-memory backends.
4. Document versioning and backwards compatibility guarantees for public API shapes.
