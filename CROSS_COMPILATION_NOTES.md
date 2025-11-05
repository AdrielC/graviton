# Cross-Compilation Notes for Scala.js

## Current Status

The file upload demo uses a **JavaScript implementation of FastCDC** that mirrors the algorithm in `graviton-streams`. While functional, it would be better to share the actual compiled code.

## Why We Don't Cross-Compile Yet

The main blocker is **zio-schema**, which doesn't have Scala.js support. Many files in `graviton-core` use zio-schema for serialization:

```scala
import zio.schema.{DeriveSchema, Schema}

final case class BlobMetadata(...)
object BlobMetadata:
  given Schema[BlobMetadata] = DeriveSchema.gen[BlobMetadata]
```

## What DOES Work with Scala.js

✅ **Iron** - Refined types work perfectly on Scala.js  
✅ **ZIO Core** - Full support  
✅ **ZIO Streams** - Full support  
✅ **ZIO JSON** - Full support  
✅ **ZIO Prelude** - Full support  

## Future Approach

When zio-schema gains Scala.js support (or we move away from it for shared models), we can:

1. **Create a shared `graviton-core-js` module** with:
   - Scan algebra (`graviton.core.scan.*`)
   - Pure chunking logic
   - Hashing utilities
   - Types using only Iron (no Schema)

2. **Keep JVM-only modules** for:
   - Storage backends (S3, PostgreSQL, RocksDB)
   - Server runtime
   - Schema-based serialization

3. **Cross-compile `graviton-streams`**:
   - Already mostly pure streaming code
   - Scan implementations work on both platforms
   - Would give us **identical chunking behavior** in browser and server

## Immediate Benefit

The current JS implementation is sufficient for the demo because:
- ✅ Algorithm is identical (rolling hash, min/avg/max boundaries)
- ✅ Shows real content-defined chunking behavior
- ✅ Demonstrates deduplication accurately
- ✅ No network roundtrip needed for chunking

## References

- [Iron with Scala.js](https://github.com/Iltotore/iron#scalajs-support)
- [ZIO Scala.js support](https://zio.dev/reference/platforms/scalajs/)
- [scala-java-time for JS](https://github.com/cquiroz/scala-java-time) - if we need time types

## Next Steps

When ready to cross-compile:

```sbt
lazy val coreShared = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/graviton-core-shared"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio" % V.zio,
      "dev.zio" %%% "zio-prelude" % V.zioPrelude,
      "io.github.iltotore" %%% "iron" % V.iron,
    )
  )
```
