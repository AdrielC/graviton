# Installation Guide

## Requirements

- Scala 3 or later
- Java 11 or later
- SBT 1.9.x or later

## Adding to Your Project

Add the core library to your `build.sbt`:

```scala
libraryDependencies += "io.quasar" %% "graviton-core" % "@VERSION@"
```

Optional modules:

```scala
libraryDependencies ++= Seq(
  "io.quasar" %% "graviton-fs"       % "@VERSION@",  // Filesystem backend
  "io.quasar" %% "graviton-s3"       % "@VERSION@",  // S3 backend
  "io.quasar" %% "graviton-tika"     % "@VERSION@",  // Media type detection
  "io.quasar" %% "graviton-metrics"  % "@VERSION@"   // Prometheus metrics
)
```

## Next Steps

- Follow the [Quick Start](quick-start.md) to ingest and fetch binaries.
- Review the [Design Goals](../design-goals.md) for architectural background.
- Browse the [Use Cases](../use-cases.md) to see real-world scenarios.
