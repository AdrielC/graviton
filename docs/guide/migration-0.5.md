# Migrating from 0.4 to 0.5

Graviton 0.5 is an intentional early-semver Scala API boundary. It repairs a published Scala 3 TASTy hierarchy that could crash a clean downstream compilation, aligns every ZIO Blocks dependency on the official `0.0.51` release, and hardens the streaming HTTP, gRPC, PDF, frame, and content-key boundaries.

Upgrade every Graviton module together and clean-recompile the application. Content keys, blocks, manifests, HTTP and gRPC wire contracts, and stored CAS data are unchanged. No data migration is required.

## Why the minor version changes

The 0.4.0 core artifact exposed `Size1`, `SizeLong1`, and `IndexLong0` with an implementation trait selected through singleton objects. Scala 3 could write that hierarchy to TASTy, but a separate compilation that followed `BinaryAttributes` into `FileSize` or `ChunkCount` could fail while reading it.

Version 0.5 moves the implementation to the statically owned `types.SizeTrait.Trait` and retains the three old names as marker types. The static trait now requires sealed numeric evidence for exactly `Int` or `Long`, avoiding a runtime typeclass cast. A cold external consumer now compiles and invokes typed `BinaryAttributes` methods. The build filters only the three documented marker hierarchy changes and the new static-trait evidence accessor, while keeping every unrelated binary-compatibility check active.

Ordinary use is unchanged:

```scala
import graviton.core.types.*

val fileSize  = FileSize.either(1024L)
val blockSize = BlockSize.either(64 * 1024)
val index     = BlockIndex.either(0L)
val count     = ChunkCount.either(1L)
```

Code that directly extended or invoked refinement operations through `Size1`, `SizeLong1`, or `IndexLong0` must be recompiled and should move to a concrete refinement:

```scala
import graviton.core.types.*

object PageSize extends SizeSubtype.Trait[1, 4096, 0, 1]
object ByteCount extends SizeLongSubtype.Trait[1L, Long.MaxValue.type, 0L, 1L]
object ByteIndex extends SizeLongSubtype.Trait[0L, Long.MaxValue.type, 0L, 1L]
```

The four-parameter `SizeSubtype.Trait[...]` and `SizeLongSubtype.Trait[...]` source forms remain supported. New code can also extend the static implementation directly when it needs to choose the numeric width explicitly.

## Dependency alignment

Use one version for every module:

```scala
val gravitonVersion = "0.5.0"

libraryDependencies ++= Seq(
  "io.github.adrielc" %% "graviton-runtime" % gravitonVersion,
  "io.github.adrielc" %% "graviton-http" % gravitonVersion,
  "io.github.adrielc" %% "graviton-pdf" % gravitonVersion,
)
```

The 0.4.0 POM could select ZIO Blocks `0.017`, an older malformed duplicate of the `0.0.17` line that Maven sorts above `0.0.51`. Version 0.5 pins schema, chunk, and media type to `0.0.51` and verifies the resolved graph from an isolated external consumer. It also consumes zio-pdf `0.2.0-RC7`, whose JVM and Scala.js artifacts use the same Blocks release.

Adriel's [opaque-wrapper register layout fix](https://github.com/zio/zio-blocks/pull/1578) is merged upstream but is newer than the current ZIO Blocks `0.0.51` release. Graviton therefore decodes primitive wire records and validates conversion into Iron-refined public types. Remove that compatibility bridge only after an official Blocks release containing the upstream fix passes the JVM and Scala.js nonzero-value vectors.

## Streaming and validation changes

- HTTP and gRPC enforce expected length inside the byte stream before manifest publication.
- PDF ingest validates `application/pdf`, sniffs the header, and chunks incrementally without collecting the document.
- scodec decoding rejects truncated EOF and caps undecoded carry at 32 MiB by default.
- Media type parameters survive browser, SDK, HTTP, gRPC, metadata, and PDF boundaries.
- Content keys reject unsupported algorithms, wrong digest lengths, uppercase or malformed hex, and negative sizes.
- Frame codecs enforce version, index, AAD, view-count, and encoded-size bounds before allocation.

Review malformed input handling if an application depended on the previous permissive behavior.

## Upgrade proof

After changing dependencies:

1. Delete incremental compiler output and perform a clean compile.
2. Confirm every resolved `zio-blocks-*` artifact is `0.0.51` and no `0.017` remains.
3. Upload and download a representative PDF and non-PDF blob through the real server.
4. Compare the returned bytes and content keys.
5. Compile any custom refined size definitions against 0.5 rather than relying on old TASTy.

See the [ZIO Blocks audit](../design/zio-blocks-audit.md) for the complete module disposition and remaining production gaps.
