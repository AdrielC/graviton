# Binary Streaming

## Overview

Torrent provides efficient binary streaming capabilities through ZIO Streams. This allows for memory-efficient processing of large files and data streams.

## Key Features

- Zero-copy operations where possible
- Backpressure handling
- Chunked streaming
- Resource-safe operations

## Usage

```scala
import torrent._
import zio.stream._

// Stream from a file
val stream = ZStream.fromFile(path)
  .via(chunker)
  .map(chunk => process(chunk))
  .runCollect

// Stream to a file
val sink = ZSink.fromFile(path)
stream.run(sink)
```

## Best Practices

1. Use appropriate chunk sizes
2. Handle backpressure properly
3. Always close resources
4. Consider memory constraints

## Related Topics

- [Content-Addressable Storage](content-addressable-storage.md)
- [Chunking Strategies](chunking-strategies.md)
- [API Reference](../api-reference/index.md) 