# Chunking API

## Overview

The Chunking API provides interfaces and implementations for splitting content into chunks for storage and deduplication. It includes various chunking strategies and configuration options.

## Core Types

### ChunkPolicy

The base trait for implementing chunking strategies:

```scala
trait ChunkPolicy:
  def shouldSplit(context: ChunkContext): Boolean
  def costFunction(current: Chunk[Byte], next: Chunk[Byte]): Long
```

### RollingHashChunker

The default content-aware chunking implementation:

```scala
object RollingHashChunker:
  case class Config(
    minChunkSize: Length,
    avgChunkSize: Length,
    maxChunkSize: Length
  )

  def apply(config: Config): Chunker
```

### ChunkDef

Represents a chunk definition:

```scala
case class ChunkDef(
  offset: Long,
  length: Length,
  hash: BinaryKey
)
```

## Usage Examples

### Basic Chunking

```scala
val config = RollingHashChunker.Config(
  minChunkSize = Length(16L * 1024L),  // 16KB
  avgChunkSize = Length(64L * 1024L),  // 64KB
  maxChunkSize = Length(256L * 1024L)  // 256KB
)

val chunker = RollingHashChunker(config)
val chunks = chunker.chunk(content)
```

### Streaming Chunking

```scala
val stream = ZStream.fromFile(path)
  .via(chunker.pipeline)
  .map(chunk => process(chunk))
  .runCollect
```

## Related Topics

- [Binary Storage](../api-reference/binary-store.md)
- [Content Detection](../api-reference/content-detection.md) 