# Chunking Strategies

## Overview

Chunking strategies determine how content is split into smaller pieces for storage and deduplication. Torrent provides several built-in strategies and allows for custom implementations.

## Built-in Strategies

### Rolling Hash Chunker

The default chunking strategy uses a rolling hash algorithm:

```scala
val config = RollingHashChunker.Config(
  minChunkSize = Length(16L * 1024L),  // 16KB
  avgChunkSize = Length(64L * 1024L),  // 64KB  
  maxChunkSize = Length(256L * 1024L)  // 256KB
)
```

Features:
- Content-aware boundaries
- Efficient rolling computation
- Configurable chunk sizes
- Deduplication-friendly

### Fixed Size Chunker

Simple fixed-size chunking for predictable splits:

```scala
val chunker = FixedSizeChunker(chunkSize = Length(64L * 1024L))
```

## Custom Strategies

Implement your own strategy by extending `ChunkPolicy`:

```scala
trait ChunkPolicy:
  def shouldSplit(context: ChunkContext): Boolean
  def costFunction(current: Chunk[Byte], next: Chunk[Byte]): Long
```

## Related Topics

- [Content-Addressable Storage](content-addressable-storage.md)
- [Binary Streaming](binary-streaming.md)
- [API Reference](../api-reference/index.md) 