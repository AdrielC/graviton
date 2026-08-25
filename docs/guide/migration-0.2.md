# Migrating from 0.1 to 0.2

Graviton 0.2 is an intentional pre-1.0 compatibility boundary. It replaces byte-materializing convenience shapes and unchecked SDK primitives with streaming APIs, refined values, and scoped transport lifetimes.

## What changed

| 0.1 surface | 0.2 surface | Reason |
| --- | --- | --- |
| Ad hoc HTTP calls | `GravitonClient` from `graviton-http` | One supported client for the operational `/api/v1/blobs` API |
| Arbitrary `Chunk[Byte]` retrieval | `Graviton.stream` for arbitrary payloads; `retrieve` returns `InMemoryBytes` and rejects values over 16 MiB | Collection now carries and enforces its bound |
| `String` content types | ZIO Blocks `MediaType` | Parse once and render only at the HTTP edge |
| Raw upload sizes and offsets | Iron-refined `Long` values | Preserve the 1 TiB contract without narrowing to `Int` |
| Explicit raw session ID on every part call | `withUploadSession` or `withSession`, backed by scoped `FiberRef` context | Prevent accidental session mixing between concurrent uploads |
| Inline schema and data bytes in upload JSON | Streamed blobs referenced by `MetadataReference` | Keep binary data out of the control plane |
| Unbounded response materialization | JSON and error responses capped at 1 MiB | Bound hostile or malformed control responses |

## Operational client

Prefer `ai.hylo.graviton.client.GravitonClient`. Uploads accept `ZStream[Byte]`; downloads return a stream whose HTTP response scope remains open until consumption ends. See [Scala Streaming SDK](./scala-sdk.md).

## Experimental resumable client

Construct `GravitonUploadHttpClient` inside `Scope` with `make` or `fromTransport`. Session-dependent calls no longer accept a `String` session parameter:

```scala
client.withUploadSession(registerRequest) {
  client.uploadPart(part, bytes) *> client.complete(completeRequest)
}
```

The session is inherited by child fibers and restored when the regional scope exits. The packaged server still does not advertise resumable routes, so this client remains experimental until matching routes have end-to-end acceptance.

## Dependency alignment

The 0.2 line aligns on ZIO 2.1.26, ZIO HTTP 3.11.4, ZIO Schema 1.8.6, ZIO Blocks 0.0.51, Iron 3.3.2, ZIO JSON 0.10.0, and Scala.js 1.22.0. Run your normal eviction and integration checks when upgrading an application.

## Release policy

The build records this boundary with `Compatibility.None`, and the release gate verifies that `v0.2.0` is the valid early-semver version. After the 0.2.0 release, development returns to binary-and-source-compatible patch intent until another boundary is declared.
