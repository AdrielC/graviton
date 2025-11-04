# Graviton Blobstore v1 gRPC API

Graviton Blobstore v1 exposes three coordinated gRPC services beneath the package `io.graviton.blobstore.v1`:

- **UploadGateway** – the frames-first ingestion pipe with schema-aware metadata, raw chunk fallback, events, and keepalives.
- **UploadService** – classic multipart parity for clients mirroring the REST API.
- **Catalog** – search, dedupe, export, and subscription capabilities over the blob catalogue.

Every upload can carry structured metadata via `MetadataNamespace`, providing optional schema bytes/content-types alongside the payload bytes/content-type. This powers validation during ingest and rich catalog queries later.

---

## UploadGateway – Frames-First Streaming

```protobuf
service UploadGateway {
  rpc Stream (stream ClientFrame) returns (stream ServerFrame);
}

message ClientFrame {
  oneof kind {
    StartUpload start    = 1;
    DataFrame   frame     = 2;
    RawChunk    chunk     = 3;
    Complete    complete  = 4;
    Subscribe   subscribe = 5;
    Cancel      cancel    = 6;
    Ping        ping      = 7;
  }
}

message ServerFrame {
  oneof kind {
    StartAck start_ack = 1;
    Ack      ack       = 2;
    Progress progress  = 3;
    Completed completed= 4;
    Event    event     = 5;
    Error    error     = 6;
    Pong     pong      = 7;
  }
}
```

### Workflow

```text
1. StartUpload – declare object content-type, TTL hints, and schema-aware metadata namespaces.
2. DataFrame / RawChunk – stream Graviton frames (preferred) or raw chunks (fallback).
3. Subscribe – opt into topics such as metadata validation or ingest lifecycle.
4. Ping / Pong – keep the session alive beyond the negotiated TTL.
5. Complete – supply optional manifest bytes and expected object hash.
6. StartAck / Ack / Progress – the server confirms session id, supported frame types, windows, and tracked byte counts.
7. Event – validation results, dedupe hints, or catalog notifications.
8. Completed – final document id, canonical blob hash, object MIME type, and optional URL.
```

`GravitonUploadGatewayClientZIO` (package `ai.hylo.graviton.client`) orchestrates this flow, enforcing ack ordering and surfacing events:

```scala
val gatewayClient = new GravitonUploadGatewayClientZIO(gatewayStub, uploadServiceStub)

val start = StartUpload(
  objectContentType = "application/pdf",
  metadata = List(
    MetadataNamespace(
      namespace = "document",
      schemaContentType = Some("application/json; profile=json-schema"),
      schemaBytes = Some(Chunk.fromArray(schemaBytes)),
      dataContentType = "application/json",
      dataBytes = Chunk.fromArray(metadataBytes),
    )
  ),
)

val frames = ZStream.fromIterable(frameBytes.zipWithIndex).map { case (bytes, idx) =>
  Left(
    DataFrame(
      sessionId = "pending",
      sequence = idx,
      offsetBytes = idx.toLong * bytes.length,
      contentType = "application/graviton-frame",
      bytes = Chunk.fromArray(bytes),
      last = idx == frameBytes.length - 1,
    ),
  )
}

for {
  outcome <- gatewayClient.uploadFrames(start, frames, complete = Complete(sessionId = "pending"))
  _       <- ZIO.logInfo(s"Stored ${outcome.completed.documentId} hash=${outcome.completed.blobHash}")
} yield outcome
```

### Error Semantics

- `SESSION_EXPIRED` – TTL elapsed; register a fresh session.
- `INVALID_CHECKSUM` – checksum mismatch detected immediately.
- `PROTOCOL_VIOLATION` – out-of-order sequence numbers, unsupported content-types, or invalid transitions.
- `UNSUPPORTED_TYPE` – attempted to stream a content-type absent from `StartAck.accepted_content_types`.

The helper client translates these into `UploadGatewayError` variants and ensures that `Ack.acknowledged_sequence` is strictly monotonic.

---

## UploadService – Classic Multipart Parity

```protobuf
service UploadService {
  rpc RegisterUpload (RegisterUploadRequest) returns (RegisterUploadResponse);
  rpc UploadParts    (stream UploadPartRequest) returns (UploadPartsResponse);
  rpc CompleteUpload (CompleteUploadRequest) returns (CompleteUploadResponse);
}
```

Use the classic service when mirroring REST multipart behaviour or integrating existing chunked upload tooling. `RegisterUploadRequest` carries the object MIME type, optional size, metadata namespaces, and an optional client session id. `UploadPartRequest` includes sequence, offset, checksum, and `last` flags. `CompleteUploadRequest` can embed manifests and expected hashes for parity with frames ingest.

`GravitonUploadGatewayClientZIO.uploadViaClassic` bridges to this API, while `GravitonUploadHttpClient` (zio-http based) offers a JSON+streaming alternative for HTTP callers.

---

## Catalog – Search, Dedupe, Export, Subscribe

```protobuf
service Catalog {
  rpc Search         (SearchRequest)          returns (stream SearchResult);
  rpc List           (ListRequest)            returns (ListResponse);
  rpc Get            (GetRequest)             returns (GetResponse);
  rpc FindDuplicates (FindDuplicatesRequest)  returns (stream DuplicateGroup);
  rpc Export         (ExportRequest)          returns (stream ExportChunk);
  rpc Subscribe      (SubscribeRequest)       returns (stream CatalogEvent);
}
```

Highlights:

- **Search** – Combine deterministic filters (hash, content-type, size ranges, namespaces) with OR groups and optional full-text hooks. Results include namespace summaries.
- **FindDuplicates** – Group objects by hash or hash-prefix+size for dedupe workflows.
- **Export** – Stream manifests and Graviton frames for migrations; pipe the frames straight into `UploadGateway`.
- **Subscribe** – Receive catalog events (validation, ingest completion, dedupe findings) for reactive automation.

`GravitonCatalogClientZIO` provides ergonomic wrappers:

```scala
val catalog = new GravitonCatalogClientZIO(catalogStub)

val results = catalog
  .search(SearchFilters(hashes = Chunk("sha256:..."), contentTypes = Chunk("application/pdf"), sizeRange = Some(0L -> 1048576L)))
  .runCollect

val duplicates = catalog.findDuplicates(FindDuplicatesRequest(hashPrefix = "sha256:abcd"))

val exportPlan = ExportPlan(documentIds = Chunk("doc-001"), includeFrames = true)
val exportStream = catalog.export(exportPlan)
```

Pair `exportStream` with `GravitonUploadGatewayClientZIO.uploadFrames` to migrate content without decoding frames.

---

## Practical Guidance

- Prefer `UploadGateway` for new ingest paths; reserve `RawChunk` for legacy fallbacks.
- Attach JSON/CBOR schemas inside `MetadataNamespace` to receive `metadata.validation` events.
- Subscribe to event topics to power dedupe quarantines or compliance checks.
- Use `Catalog.FindDuplicates` before finalising uploads and leverage `Catalog.Export` + `UploadGateway` for parity checks or migrations.

For HTTP parity and JSON payloads, see [HTTP API](./http). For module wiring and build notes, consult [Protocol Modules](../modules/protocol).
