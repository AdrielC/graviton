# Torrent: A ZIO-based Content-Addressable Storage System

**Torrent** is a ZIO-native chunked content-addressable storage (CAS) system focused on **streaming binary processing**, **content-defined chunking**, and **type-safe operations**. It provides efficient handling of binary data with deterministic chunking strategies.

## Current Implementation Status

### ✅ Implemented Features

**Core Types:**
- `Bytes` - Type-safe immutable byte sequences with rich operations
- `Length` and `Index` - Refined types for safe array operations  
- `BinaryKey` - Content-addressable identifiers
- Comprehensive encoding/decoding (hex, base64, UTF-8)
- Safe numeric conversions with proper error handling
- Bitwise operations (AND, OR, XOR, NOT)

**Chunking:**
- `RollingHashChunker` - Content-defined chunking using Rabin-Karp algorithm
- Configurable chunk sizes (min/avg/max)
- Stable boundary detection that survives content shifts
- ZIO Stream integration with pipelines and sinks

**Performance:**
- Zero-copy operations where possible
- Constant memory usage for streaming operations
- Efficient chunk processing with ZIO's native chunk support

### 🚧 In Development

**Storage Backends:**
- Filesystem storage
- JDBC database storage
- S3/MinIO object storage
- PostgreSQL large objects

**Advanced Features:**
- PDF-aware chunking
- Token-based semantic chunking
- Deduplication strategies
- Manifest-based reassembly

## Design Principles

### Type Safety First

All operations use refined types to prevent common errors:

```scala
val length: Length = Length(42L)  // Must be > 0
val index: Index = Index(0L)      // Must be >= 0
val bytes: Bytes = Bytes(1, 2, 3) // Non-empty, size-constrained
```

### Content-Defined Boundaries

Traditional fixed-size chunking (`.grouped(N)`) is brittle when content shifts. Torrent uses rolling hash to create stable boundaries:

```scala
val config = RollingHashChunker.Config(
  minChunkSize = Length(16L * 1024L),  // 16KB
  avgChunkSize = Length(64L * 1024L),  // 64KB  
  maxChunkSize = Length(256L * 1024L)  // 256KB
)
```

### Streaming-First Architecture

All operations work with ZIO Streams for memory efficiency:

```scala
ZStream.fromFile(path)
  .via(RollingHashChunker.pipeline(config))
  .map(_.toHex)
  .runCollect
```

## Key Components

### `RollingHashChunker`

Uses a Rabin-Karp-style rolling hash with configurable parameters:

- **Polynomial-based hash**: `hash * polynomial + (byte & 0xff)`
- **Boundary detection**: `(hash & mask) == 0` or max size reached
- **Stable cut points**: Same content produces same chunks even with shifts
- **Configurable sizes**: Min/avg/max chunk size constraints

### `Bytes` Type

Opaque type wrapper around `zio.Chunk[Byte]` with:

- **Rich operations**: take, drop, slice, append, bitwise ops
- **Safe conversions**: to/from numbers, strings, UUIDs
- **Encoding support**: hex, base64, UTF-8, binary
- **Stream integration**: native ZIO Stream conversion
- **Performance**: zero-copy operations where possible

### Error Handling

All fallible operations return `Either` or `Option`:

```scala
val maybeBytes: Either[String, Bytes] = Bytes(array)
val maybeInt: Either[IllegalArgumentException, Long] = bytes.toInt()
val maybeDropped: Option[Bytes] = bytes.drop(Length(10L))
```

## Future Roadmap

### Semantic Chunking

Planned support for format-aware chunking:

```scala
trait ChunkPolicy:
  def shouldSplit(context: ChunkContext): Boolean
  def costFunction(current: Chunk[Byte], next: Chunk[Byte]): Long
```

**Policies planned:**
- PDF stream boundaries (`stream ... endstream`)
- JSON object boundaries
- Token-aware splitting for structured data

### Storage Integration

```scala
trait BinaryStore:
  def insert: ZSink[Any, Throwable, Byte, Byte, BinaryKey.Generated]
  def findBinary(key: BinaryKey): IO[Option[ZStream[Any, Throwable, Byte]]]
  def delete(key: BinaryKey): IO[Throwable, Boolean]
```

### Deduplication

Content-addressable storage with automatic deduplication:
- Same chunks produce identical keys
- Space savings through shared storage
- Manifest-based reassembly

## Performance Characteristics

Current implementation provides:

- **Constant memory usage** for streaming operations
- **Efficient chunking** with content-aware boundaries  
- **Type safety** without runtime overhead
- **Zero-copy** operations where possible
- **Immutable operations** that are thread-safe

## Why "Torrent"?

Like BitTorrent, Torrent streams, chunks, hashes, and distributes content. But here it's used for **content-addressable documents** and **deterministic processing** rather than file sharing.

The name reflects the core concepts:
- **Streaming**: Continuous data flow
- **Chunking**: Breaking content into manageable pieces
- **Hashing**: Content-addressable identification
- **Distribution**: Efficient storage and retrieval

---

Torrent provides the foundation for deterministic, efficient, and type-safe content processing pipelines built with ZIO.
