# Migrating from 0.2 to 0.3

Graviton 0.3 is an intentional pre-1.0 compatibility boundary. It removes transport contracts that were published before a matching operational server existed, replaces default unsupported backend methods with required lifecycle operations, and promotes one tested streaming gRPC API.

Stored content keys, filesystem manifests, framed manifests, and the canonical HTTP `/api/v1` lifecycle remain readable. The break is in Scala and protobuf API shape, not persisted CAS identity.

## What changed

| 0.2 surface | 0.3 surface | Migration |
| --- | --- | --- |
| Generated catalog, upload-gateway, and upload-node protobuf APIs | `io.graviton.blobstore.v1.BlobService` | Use the operational blob lifecycle described in [gRPC API](../api/grpc.md). The removed services never had a complete packaged-server implementation. |
| `GravitonCatalogClientZIO`, `GravitonUploadGatewayClientZIO`, and `UploadNodeGrpcClient` | `graviton.protocol.grpc.GravitonGrpcClient` | Construct the scoped client and call `put`, `get`, `stat`, `list`, `inspect`, `delete`, or `health`. |
| Experimental `GravitonUploadHttpClient` and `UploadNodeHttpClient` | `ai.hylo.graviton.client.GravitonClient` or `GravitonGrpcClient` | Use a client whose complete contract is served and socket-tested. Resumable HTTP will return only with a durable server implementation. |
| Caller-managed upload session strings | The client-streaming `PutBlob` call | Stream metadata first, then bounded data frames. The RPC stream is the session. |
| Optional default methods on `BlobStore` and `BlobManifestRepo` | Required lifecycle methods | Custom backends must implement `stat`, `list`, `inspect`, `delete`, and `health` instead of inheriting unsupported behavior. |
| Generic `BinaryKey` blob arguments | `BinaryKey.Blob` | Parse and validate at the application boundary, then retain the semantic key variant. |
| Raw `String` key/value identifiers | Iron-refined `KvKey` and bounded `KvValue` | Use the smart constructors before calling PostgreSQL or RocksDB adapters. Values are limited to 32 MiB. |
| Placeholder PostgreSQL and S3 object methods | Transactional chunked PostgreSQL and bounded multipart S3 implementations | Remove application-side workarounds for the formerly unsupported operations. |

## Streaming gRPC client

Uploads and downloads remain streams for their complete lifetime. No API in the supported gRPC path accepts or returns an arbitrary whole-payload byte array.

```scala
import graviton.protocol.grpc.GravitonGrpcClient
import zio.*
import zio.blocks.mediatype.MediaType
import zio.stream.*

val copied = ZIO.scoped {
  for
    client <- GravitonGrpcClient.scoped("127.0.0.1", 9090)
    put    <- client.put(
                ZStream.fromFileName("archive.tar"),
                MediaType.unsafeFromString("application/x-tar"),
              )
    _      <- client.get(put.key).run(ZSink.fromFileName("archive-restored.tar"))
  yield put
}
```

Each protobuf data frame is capped at 1 MiB and both peers reject messages above 2 MiB. Files may be much larger because neither side collects the entire stream.

## PostgreSQL schema

Apply the 0.3 DDL before enabling the PostgreSQL key-value or generic object adapters:

```bash
psql -U postgres -d graviton -f modules/pg/ddl.sql
```

The new `graviton.key_value`, `graviton.object_data`, and `graviton.object_chunk` tables use database-level size constraints. Generic objects are stored as ordered chunks no larger than 1 MiB and are committed transactionally.

## Deployment

Expose `GRAVITON_GRPC_PORT` in addition to the HTTP port. The packaged defaults are `8081` for HTTP and `9090` for gRPC. When authentication is enabled, send the same bearer token to gRPC metadata. Backend health remains public for orchestrator probes.

## Compatibility policy

The build records this release with `Compatibility.None` because 0.3 deliberately removes non-operational public APIs. This is permitted for a documented 0.x minor boundary. Patch releases after 0.3.0 return to binary-and-source-compatible intent.
