# End-to-end Upload

The packaged HTTP and gRPC listeners stream request bytes into the same `CasBlobStore` sink. The upload stream is the session: callers do not create or thread a session identifier.

## Pipeline overview

```mermaid
flowchart LR
  classDef stage fill:#0f1419,stroke:#00ff41,color:#e6e6e6;
  classDef io fill:#0f1419,stroke:#00c8ff,color:#e6e6e6;

  Client["Client"]:::io
  Transport["HTTP body / gRPC frames"]:::io
  Validate["Declared size check"]:::stage
  Probe["Bounded MIME probe"]:::stage
  Select["Scoped provider"]:::stage
  Normalize["64 KiB bounded input"]:::stage
  Chunk["Selected Chunker"]:::stage
  Key["Hash + block key"]:::stage
  Store["BlockStore"]:::io
  Spool["Scoped manifest spool"]:::stage
  Manifest["BlobManifestRepo"]:::io
  Result["BlobWriteResult"]:::io

  Client --> Transport
  Transport -->|"one ZStream[Byte]"| Validate
  Validate --> Probe
  Probe --> Select
  Select --> Normalize
  Normalize --> Chunk
  Chunk --> Key
  Key -->|"CanonicalBlock"| Store
  Store -->|"key + offset + size"| Spool
  Spool -->|"bounded replay after blob hash"| Manifest
  Manifest --> Result
```

## Upload stages

1. **Transport boundary**: HTTP accepts the request body and gRPC `PutBlob` accepts a metadata frame followed by bounded data frames. Both preserve backpressure and pass one `ZStream[Byte]` into `UploadIngestor`.
2. **Inline validation**: an optional declared byte size is checked while that stream is consumed. Overflow aborts at the first observed excess and underflow fails at EOF.
3. **Bounded classification**: the runtime retains at most 4 KiB, runs registered detectors over that immutable prefix, and replays those bytes exactly once. Today the packaged registry detects `%PDF-`; unrecognized formats use the default provider.
4. **Scoped provider**: an exact effective-media-type key is resolved through the provider registry. One fresh chunker is acquired for the upload and remains scoped across the complete CAS write.
5. **Bounded input**: `CasBlobStore` copies arbitrary upstream chunks into fixed 64 KiB chunks and feeds bounded queues. It hashes the logical blob incrementally and rejects the 1 TiB public limit without collecting the body.
6. **Chunking and keying**: the selected `Chunker` emits refined blocks. `CasIngest.blockKeyDeriver` hashes one block at a time and constructs its `BinaryKey.Block`.
7. **Block persistence**: each `CanonicalBlock` is written through the configured `BlockStore`. Filesystem writes are atomic; the S3 adapter uses scoped multipart streaming where required. Duplicate block keys reuse existing content.
8. **Manifest staging**: one entry per stored block is appended to a scoped disk spool. Heap state remains scalar while the blob digest and total size are computed.
9. **Manifest commit**: after successful EOF, the spool is replayed into the filesystem or PostgreSQL `BlobManifestRepo` in bounded writes. Only then is the blob manifest published and `BlobWriteResult` returned.

HTTP, the packaged gRPC server, and a Shardcake upload owner all call the same `UploadIngestor`. Validation and classification are not separate consumers of the body. Only detector effects over the bounded prefix may run in parallel.

If an upload fails after blocks have been persisted, no manifest is committed, but those unreferenced blocks can remain until orphan cleanup. The collector streams manifest summaries and block inventory into an exact, temporary disk-backed mark join. It does not load a repository-wide block set or inventory `Chunk` into heap. Built-in upload and download paths hold a shared permit for their complete stream lifetime; garbage collection holds the exclusive form across its complete run. Filesystem mode implements that protocol with a file lock, while S3 plus PostgreSQL mode uses a namespaced advisory lock. Minimum age and the second mark remain defense in depth for abandoned blocks and compatibility callers.

## Transducer boundary

The operational path uses the [Transducer algebra](./core/transducers.md) for its pure per-block key derivation stage:

```scala
import graviton.core.scan.CasIngest
import graviton.core.scan.FS.toPipeline

val keyedBlocks = blocks.via(CasIngest.blockKeyDeriver().toPipeline)
```

`CasBlobStore` deliberately retains ZIO Streams, scoped queues, the manifest spool, and backend effects around that pure stage. Experimental aggregate transducer examples and register-backed summaries are not the production upload orchestrator. See [Transducer Algebra](./core/transducers.md) for the implemented composition rules and limitations.

## See also

- [Binary Streaming Guide](./guide/binary-streaming.md) — detailed walkthrough of blocks, manifests, and attributes
- [Transducer Algebra](./core/transducers.md) — the composable pipeline engine
- [Connect Your Server](./demo.md) to operate the implemented HTTP storage lifecycle against an endpoint you provide
- [Chunking Strategies](./ingest/chunking.md) — fixed, FastCDC, delimiter, and PDF-aware algorithms
