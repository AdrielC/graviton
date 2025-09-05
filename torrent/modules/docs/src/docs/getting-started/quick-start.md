# Quick Start Guide

## Installation

Add Torrent to your project:

```scala
libraryDependencies += "com.tybera" %% "torrent-core" % "@VERSION@"
```

## Basic Usage

```scala
import torrent._

val storage = TorrentStorage.create()
for {
  // Store some content
  key <- storage.store("Hello, World!".getBytes)
  
  // Retrieve it later
  content <- storage.retrieve(key)
} yield content
```

## Streaming Example

```scala
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

## Next Steps

- Learn about [Content-Addressable Storage](../core-concepts/content-addressable-storage.md)
- Explore the [API Reference](../api-reference/binary-store.md)
- Check out the [Guides](../guides/storage.md) 