# Installation Guide

## Requirements

- Scala @SCALA_VERSION@ or later
- Java 11 or later
- SBT 1.9.x or later

## Adding to Your Project

Add the following to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "com.tybera" %% "torrent-core" % "@VERSION@",
  // Optional modules
  "com.tybera" %% "torrent-fs" % "@VERSION@",    // Filesystem backend
  "com.tybera" %% "torrent-s3" % "@VERSION@",    // S3 backend
  "com.tybera" %% "torrent-pg" % "@VERSION@"     // PostgreSQL backend
)
```

## Module Overview

- `torrent-core`: Core functionality and interfaces
- `torrent-fs`: Local filesystem storage backend
- `torrent-s3`: Amazon S3 storage backend
- `torrent-pg`: PostgreSQL storage backend

## Next Steps

- Follow the [Quick Start Guide](quick-start.md)
- Learn about [Core Concepts](../core-concepts/content-addressable-storage.md)
- Browse the [API Reference](../api-reference/binary-store.md) 