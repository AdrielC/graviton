# Graviton Blobstore v1 gRPC API

Graviton serves the operational gRPC API on port `9090` by default, alongside HTTP on `8081`. Set `GRAVITON_GRPC_PORT` to change the listener.

The package `io.graviton.blobstore.v1` exposes two generated services:

- `BlobService` for streaming put/get plus stat, list, inspect, and delete
- `AdminService` for storage-backed readiness

The Scala client is `graviton.protocol.grpc.GravitonGrpcClient` from `graviton-grpc_3`. It accepts and returns ZIO streams and uses ZIO Blocks `MediaType` values at the API boundary.

## Upload

`PutBlob` is client-streaming. The first frame must contain metadata. Every following frame contains at most 1 MiB of data.

```protobuf
rpc PutBlob(stream PutBlobRequest) returns (PutBlobResponse);

message PutBlobMetadata {
  optional uint64 expected_size = 1;
  string content_type = 2;
}

message PutBlobRequest {
  oneof kind {
    PutBlobMetadata metadata = 1;
    bytes data = 2;
  }
}
```

The stream itself is the upload session. There is no caller-managed session string to lose, reuse, or thread through application code. The packaged server sends request bytes through the same `UploadIngestor` as HTTP and Shardcake. An optional expected size is enforced incrementally: overflow stops the byte stream immediately, and underflow fails at EOF before the manifest is committed. A bounded prefix selects a registered media-aware provider, currently PDF for `%PDF-`, or the default provider for unknown content. The server does not collect the upload. Blocks already written before a failed upload can remain unreferenced until orphan cleanup runs.

```scala
import graviton.protocol.grpc.GravitonGrpcClient
import zio.*
import zio.blocks.mediatype.MediaType
import zio.stream.*

val upload = ZIO.scoped {
  for
    client <- GravitonGrpcClient.scoped("127.0.0.1", 9090)
    result <- client.put(
                ZStream.fromFileName("archive.tar"),
                MediaType.unsafeFromString("application/x-tar"),
              )
  yield result.key
}
```

## Download and lifecycle

```protobuf
rpc GetBlob(GetBlobRequest) returns (stream BlobChunk);
rpc StatBlob(BlobKey) returns (StatBlobResponse);
rpc ListBlobs(ListBlobsRequest) returns (stream BlobSummary);
rpc InspectBlob(InspectBlobRequest) returns (stream BlobBlock);
rpc DeleteBlob(DeleteBlobRequest) returns (DeleteBlobResponse);
```

`GetBlob` emits frames no larger than 1 MiB. Each frame carries an absolute offset, and the Scala client rejects gaps or reordering as `DATA_LOSS` before exposing bytes downstream.

```scala
ZIO.scoped {
  for
    client <- GravitonGrpcClient.scoped("127.0.0.1", 9090)
    _      <- client.get(key).run(ZSink.fromFileName("archive-restored.tar"))
    stat   <- client.stat(key)
    blocks <- client.inspect(key).runCollect
  yield (stat, blocks)
}
```

`ListBlobs` streams directly from `BlobStore.streamInventory`, which follows backend-native cursor pages and never collects repository inventory. `InspectBlob` is also server-streaming at the transport boundary, but the current logical inspect operation materializes one explicitly requested manifest and rejects manifests above its configured materialization bound. Repository-scale inventory and single-blob inspection therefore have different memory contracts.

## Security and audit

When security is enabled, the packaged listener validates the bearer token, requires the matching blob capability, and records authentication and authorization decisions before service execution. It charges each RPC against the caller's request budget, each received `PutBlob` data frame against the upload-byte budget, and each emitted `GetBlob` frame against the download-byte budget. Byte accounting happens at the transport boundary without collecting payloads. A denied frame closes the call with `RESOURCE_EXHAUSTED` before that frame reaches storage or the client. Audit persistence is fail-closed: an authenticated RPC does not begin if its audit decision cannot be stored. `AdminService.Health` remains public so orchestrators can probe backend readiness without a token.

With the packaged multi-tenant data plane enabled, the verified token organization selects the server-owned policy and store. An unknown, suspended, or wrong-cell organization receives `PERMISSION_DENIED`; callers cannot enumerate tenant policies. Retained-byte quota, object-size, and concurrent-operation exhaustion return `RESOURCE_EXHAUSTED` without exposing configured limits. Bounded admission saturation returns `UNAVAILABLE`. The database serializes retained quota with manifest publication across every node.

## Limits and errors

- Data frames are capped at 1 MiB with an Iron-refined `Chunk[Byte]` boundary.
- The server and client cap inbound gRPC messages at 2 MiB.
- Invalid keys, media types, frame order, or expected sizes return `INVALID_ARGUMENT`.
- Missing blobs return `NOT_FOUND`.
- Broken download offsets return `DATA_LOSS` in the Scala client.
- Backend health failures return `UNAVAILABLE` from `AdminService.Health`.
- Unexpected storage failures return `INTERNAL` without exposing arbitrary exception messages.

The loopback integration suite starts the real Netty gRPC server on an ephemeral port and proves a 12 MiB put/get/stat/list/inspect/delete lifecycle through generated stubs. It separately proves authenticated allow and deny decisions, exact upload/download byte metering, and rejected-frame behavior through the complete interceptor chain. The packaged-server smoke repeats a 3 MiB lifecycle against both open and authenticated assembled JAR processes. Backend integration suites separately prove PostgreSQL and S3 multipart behavior.

For JSON/HTTP clients, ranges, conditional requests, and server-side verification, see the [HTTP API](./http.md).
