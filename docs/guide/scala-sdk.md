# Scala Streaming SDK

`GravitonClient` is the typed JVM client for the operational `/api/v1/blobs` API. Upload and download payloads stay as `ZStream[Byte]`; only JSON control responses may be collected, under an enforced 1 MiB limit.

## Dependency

Use the `graviton-http` artifact with the same version as your server. It contains the client, shared response models, ZIO HTTP transport, and ZIO Blocks media types.

```scala
libraryDependencies += "io.github.adrielc" %% "graviton-http" % "<version>"
```

## Upload a file

```scala
import ai.hylo.graviton.client.GravitonClient
import zio.*
import zio.blocks.mediatype.MediaType
import zio.http.{Client, URL}
import zio.stream.ZStream

import java.nio.file.Files

def uploadFile(path: java.nio.file.Path) =
  for
    baseUrl <- ZIO.fromEither(URL.decode("http://localhost:8081"))
    client  <- GravitonClient.make(GravitonClient.Config(baseUrl))
    length  <- ZIO.fromEither(GravitonClient.BlobByteLength.either(Files.size(path)))
    result  <- client.upload(
                 GravitonClient.Upload(
                   bytes = ZStream.fromFile(path.toFile, chunkSize = 64 * 1024),
                   contentType = MediaType.unsafeFromString("application/octet-stream"),
                   contentLength = Some(length),
                 )
               )
  yield result.blob.id

val program = uploadFile(java.nio.file.Path.of("archive.bin"))
  .provide(Client.default)
```

`BlobByteLength` is an Iron-refined positive `Long` capped at 1 TiB. A known length becomes a streaming HTTP body with `Content-Length`; use `None` to send an unknown-length chunked body. Neither path calls `runCollect`.

## Keep one upload session on one node

When the server enables Shardcake locality, use the typed session API:

```scala
import graviton.runtime.upload.{TenantId, UploadSessionId, UploadSessionKey}

val session = UploadSessionKey(
  TenantId.applyUnsafe("9f2f172c-8e6b-4aef-8be8-4c750420d971"),
  UploadSessionId.applyUnsafe("ab573594-abaa-44fa-867a-8c733bf87f6c"),
)

client.uploadLocalized(upload, session)
```

This API adds only the typed control headers. It does not buffer or make the source replayable. A transport failure leaves retry policy with the caller, which knows whether it can reopen the file or regenerate the stream. Reusing the same session keeps later attempts sticky while content addressing keeps duplicate bytes safe.

## Resume after transport or process failure

For a live call whose source can continue, `uploadResumable` keeps the session ID internal, rechunks the stream into Iron-refined 1 to 64 MiB parts, and retries transient create, append, and commit failures with the same idempotency identities. It never materializes the complete upload.

```scala
val completed = client.uploadResumable(
  upload,
  partSize = GravitonClient.ResumablePartSize.applyUnsafe(8 * 1024 * 1024),
)
```

Each part is the only payload value materialized by this method. The default is 8 MiB. A retry reopens the ZIO stream backed by that bounded immutable chunk; if the first response was lost after durable acceptance, the repeated part ID returns the committed offset without consuming another body.

Process recovery needs an application-owned durable checkpoint. Supply `onCheckpoint` to persist every create, offset advance, and final commit outside the client process:

```scala
client.uploadResumable(
  upload,
  onCheckpoint = checkpointStore.save,
)
```

After restart, read that `ResumableUploadStatus`, reopen the source at `checkpoint.offset`, and call:

```scala
import java.nio.channels.Channels
import java.nio.file.StandardOpenOption

val remaining =
  ZStream
    .acquireReleaseWith(
      ZIO.attemptBlocking {
        val channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.READ)
        channel.position(checkpoint.offset.value)
        Channels.newInputStream(channel)
      },
    )(input => ZIO.attemptBlocking(input.close()).orDie)
    .flatMap(input => ZStream.fromInputStream(input, chunkSize = 64 * 1024))

client.resumeResumable(
  checkpoint,
  remaining = remaining,
  onCheckpoint = checkpointStore.save,
).flatMap(status => client.commitResumable(status.id))
```

The callback is deliberately an application boundary, not a `FiberRef`: fiber-local context is useful for transparent request metadata but cannot survive a process crash. Lower-level `createResumable`, `resumableStatus`, `resumeResumable`, `commitResumable`, and `cancelResumable` methods expose the typed recovery protocol without exposing unchecked strings.

## Download without collecting

```scala
import zio.stream.ZSink

val writeToDisk =
  client
    .download(blobId)
    .run(ZSink.fromFile(java.io.File("restored.bin")))
```

The returned stream owns the ZIO HTTP response scope until consumption ends. Early termination, failure, or interruption releases the connection. Byte ranges use a validated `DownloadRange`:

```scala
val firstMiB =
  ZIO
    .fromEither(GravitonClient.DownloadRange.make(0L, 1024L * 1024L - 1L))
    .flatMap(range => client.download(blobId, Some(range)).run(ZSink.fromFile(java.io.File("first-mib.bin"))))
```

## Lifecycle operations

The same client provides typed `list`, `metadata`, `verify`, and `delete` operations. `BlobId`, sizes, pagination limits, and byte ranges are refined values rather than unchecked primitives.

```scala
for
  details  <- client.metadata(blobId)
  verified <- client.verify(blobId)
  page     <- client.list(GravitonClient.ListLimit.applyUnsafe(100))
yield (details, verified, page)
```

## Large-object contract

Graviton's supported logical blob size is 1 TiB. The SDK test suite constructs a 1 TiB request contract and proves that its body has the correct `Long` length, has no materialized content, and does not pull the source. A socket-level test transfers, downloads, hashes, inspects, lists, and verifies 32 MiB through the actual SDK, ZIO HTTP server, and in-memory CAS. Another socket test sends 6 MiB through three 2 MiB resumable parts, records every checkpoint, commits, downloads, and re-reads the final durable status.

That is evidence of bounded-memory structure and a real end-to-end transport path. It is not a claim that CI physically transferred 1 TiB. Qualify the actual maximum object size, bandwidth, timeout, storage quota, and failure recovery in your target environment.

When security is enabled, `GRAVITON_SECURITY_MAX_REQUEST_BYTES` must be at least the intended object size. The setting accepts 1 byte through 1 TiB and defaults to 5 GiB.

Use direct upload when the source is cheap to reopen and one request is sufficient. Use resumable HTTP when the application needs durable offsets, bounded retry units, or process recovery. The streaming [gRPC client](../api/grpc.md) remains a one-live-stream contract rather than a durable multipart protocol.
