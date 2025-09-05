# Storage Guide

## Basic Usage

### Creating a Storage Instance

```scala
import torrent._

val storage = TorrentStorage.create()
```

### Storing Content

```scala
for {
  // Store a string
  key1 <- storage.store("Hello, World!".getBytes)
  
  // Store a file
  key2 <- storage.storeFile(path)
  
  // Store a stream
  key3 <- ZStream.fromFile(path).run(storage.storeSink)
} yield ()
```

### Retrieving Content

```scala
for {
  // Get as bytes
  bytes <- storage.retrieve(key)
  
  // Get as stream
  stream <- storage.retrieveStream(key)
  
  // Write to file
  _ <- storage.retrieveToFile(key, outputPath)
} yield ()
```

## Custom Backends

Torrent supports multiple storage backends:

```scala
// Filesystem backend
import torrent.fs._
val fsStorage = FsTorrentStorage.create(rootPath)

// S3 backend
import torrent.s3._
val s3Storage = S3TorrentStorage.create(bucket)

// PostgreSQL backend
import torrent.pg._
val pgStorage = PgTorrentStorage.create(config)
```

## Best Practices

1. Use appropriate chunk sizes
2. Handle errors properly
3. Clean up temporary resources
4. Consider caching for frequently accessed content

## Related Topics

- [Content Detection](torrent.detect.md)
- [Performance Optimization](../performance/optimization-guide.md)
- [API Reference](../api-reference/index.md) 