# Performance Optimization Guide

## Overview

This guide covers best practices and techniques for optimizing Torrent's performance in your application.

## Chunking Optimization

### Chunk Size Selection

Choose chunk sizes based on your use case:
- Small files: 16KB - 64KB chunks
- Large files: 64KB - 256KB chunks
- Streaming: 256KB - 1MB chunks

```scala
val config = RollingHashChunker.Config(
  minChunkSize = Length(64L * 1024L),   // 64KB
  avgChunkSize = Length(256L * 1024L),  // 256KB
  maxChunkSize = Length(1024L * 1024L)  // 1MB
)
```

### Memory Management

1. Use streaming operations
2. Implement backpressure
3. Clean up resources promptly
4. Monitor memory usage

## Storage Optimization

### Backend Selection

Choose the appropriate backend:
- Local files: `FsTorrentStorage`
- Cloud storage: `S3TorrentStorage`
- Database: `PgTorrentStorage`

### Caching

Implement caching for frequently accessed content:
```scala
val cached = storage.withCache(
  maxSize = 100,
  ttl = Duration(1, TimeUnit.HOURS)
)
```

## Related Topics

- [Storage Guide](../guides/storage.md)
- [API Reference](../api-reference/index.md) 