# Design Goals

Graviton is a ZIO‑native content‑addressable storage system focused on
streaming binary processing and deterministic chunking.  It builds on the
Binny `BinaryStore` model while incorporating lessons from the Torrent
prototype.

## Current Implementation Status

### Implemented Features

- **Core types** – `Bytes`, `Length`, `Index`, and `BinaryKey` provide typed
  wrappers around raw values with safe constructors and rich operations.
- **Rolling hash chunker** – content‑defined chunking using a Rabin–Karp style
  rolling hash with configurable minimum, average, and maximum chunk sizes.
- **Zero‑copy streaming** – all I/O is expressed with `ZStream` and `ZSink`
  allowing constant‑memory processing of large files.
- **Blob stores** – pluggable filesystem and S3 backends for block storage.
- **Media type detection** – optional Apache Tika module that tags files as they
  are ingested.
- **Structured logging** – correlation‑ID aware logs via ZIO Logging.
- **Prometheus metrics** – gauges and histograms for core operations.

### In Development

- **Additional backends** – JDBC and PostgreSQL large object stores.
- **CLI and HTTP gateway** – end‑to‑end tooling for ingestion and retrieval.
- **Format‑aware chunking** – PDF, JSON, and token‑aware policies layered on top
  of the rolling hash chunker.
- **Deduplication manifests** – explicit manifest types for reassembling files
  and verifying block layouts.
- **Release pipeline** – CI builds that publish libraries and Docker images.

## Design Principles

### Type Safety First

Refined value types prevent common indexing and conversion mistakes.  All
fallible operations return `Either` or `Option` so failures are explicit.

### Content‑Defined Boundaries

Fixed‑size grouping is brittle when content shifts.  Graviton uses a rolling
hash to detect stable cut points so that similar files share chunk layouts even
when bytes are inserted or removed.

### Streaming‑First Architecture

Every operation works on streams so large files can be processed with constant
memory.  The API exposes sinks for ingestion and streams for reads so hashing,
chunking, and storage happen in a single pass.

## Key Components

### `RollingHashChunker`

A Rabin–Karp based chunker that:

- computes polynomial rolling hashes
- emits a new chunk when `(hash & mask) == 0` or the configured maximum size is
  reached
- survives content shifts to maximize deduplication

### `Bytes` Type

An opaque wrapper around `zio.Chunk[Byte]` that offers:

- safe numeric conversions and encodings (hex, base64, UTF‑8)
- bitwise operations and slicing
- zero‑copy views into the underlying data

### Error Handling

Operations that may fail communicate errors through the type system:

```scala
val maybeBytes: Either[String, Bytes] = Bytes(array)
val maybeInt: Either[IllegalArgumentException, Long] = bytes.toLong
val maybeDropped: Option[Bytes] = bytes.drop(Length(10L))
```

## Future Roadmap

Planned enhancements include semantic chunking policies, richer storage
integrations, and manifest‑based deduplication strategies.  See the
[ROADMAP](../../../../ROADMAP.md) for milestone tracking toward v0.1.0.

