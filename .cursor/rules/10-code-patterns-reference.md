# Code Patterns Quick Reference

This file provides copy-paste-ready patterns for common Graviton coding tasks.

---

## New Refined String Type

```scala
// In graviton.core.types:
type MyName = MyName.T
object MyName extends RefinedTypeExt[String, Match["[A-Za-z0-9_-]+"] & MinLength[1] & MaxLength[128]]
```

## New Refined Numeric Type (Size Family)

```scala
// 1-based positive int, max 1024:
type MySize = MySize.T
object MySize extends SizeSubtype.Trait[1, 1024, 0, 1]

// 0-based non-negative long:
type MyIndex = MyIndex.T
object MyIndex extends IndexLong0
```

## New Opaque Subtype

```scala
opaque type MyOffset <: Offset = Offset
object MyOffset:
  inline def unsafe(value: Long): MyOffset = Offset.unsafe(value).asInstanceOf[MyOffset]
  inline def either(value: Long): Either[String, MyOffset] = Offset.either(value).map(_.asInstanceOf[MyOffset])
  given Ordering[MyOffset] = summon[Ordering[Offset]].asInstanceOf[Ordering[MyOffset]]
  given Schema[MyOffset] = summon[Schema[Offset]].asInstanceOf[Schema[MyOffset]]
```

## New Service Trait + Layer

```scala
trait MyService:
  def doWork(input: Input): ZIO[Any, Throwable, Output]

final class MyServiceLive(dep: Dep) extends MyService:
  override def doWork(input: Input): ZIO[Any, Throwable, Output] = ...

object MyServiceLive:
  val layer: ZLayer[Dep, Nothing, MyService] =
    ZLayer.fromFunction(new MyServiceLive(_))
```

## New Chunker Strategy

```scala
// Via the existing Chunker factory methods:
val myChunker = Chunker.fixed(UploadChunkSize(512 * 1024))
val cdcChunker = Chunker.fastCdc(min = 256, avg = 1024, max = 4096)
val delimChunker = Chunker.delimiter(Chunk.fromArray("\n".getBytes), includeDelimiter = true)

// Use locally in a fiber:
Chunker.locally(myChunker) {
  stream.run(blobStore.put())
}
```

## New ZPipeline (Stateless)

```scala
val myPipeline: ZPipeline[Any, Nothing, String, Int] =
  ZPipeline.map[String, Int](_.length)
```

## New ZPipeline (Stateful via ZChannel)

```scala
def accumulate: ZPipeline[Any, Nothing, Int, Int] =
  ZPipeline.fromChannel {
    def loop(sum: Int): ZChannel[Any, Nothing, Chunk[Int], Any, Nothing, Chunk[Int], Unit] =
      ZChannel.readWith(
        (in: Chunk[Int]) =>
          var s = sum
          val out = in.map { i => s += i; s }
          ZChannel.write(out) *> loop(s),
        _ => ZChannel.unit,
        _ => ZChannel.unit,
      )
    loop(0)
  }
```

## New Scan (Direct Trait)

```scala
val runningAvg: Scan.Aux[Double, Double, (Double, Long), Any] =
  Scan.fold[(Double, Long)](initial = (0.0, 0L)) { case ((sum, count), input: Double) =>
    val newSum = sum + input
    val newCount = count + 1
    ((newSum, newCount), newSum / newCount)
  } { case (sum, count) =>
    ((sum, count), Chunk.empty)
  }
```

## New FreeScan

```scala
val myFreeScan: FreeScan[Prim, Chunk[Byte], Long] =
  FS.byteCounter
```

## Compose Scans

```scala
// Sequential:
val pipeline = hashScan >>> manifestScan

// Fanout with labels:
val telemetry = (FS.counter[Byte].labelled["count"] &&& FS.byteCounter.labelled["bytes"])
```

## Test Pattern (zio-test)

```scala
object MySpec extends ZIOSpecDefault:
  def spec = suite("My")(
    test("roundtrip") {
      check(Gen.chunkOf1(Gen.byte)) { bytes =>
        for
          encoded <- ZIO.fromEither(encode(bytes))
          decoded <- ZIO.fromEither(decode(encoded))
        yield assertTrue(decoded == bytes)
      }
    },
  )
```

## Streaming Test Pattern

```scala
test("pipeline preserves byte count") {
  val input = Chunk.fromArray(Array.fill(10000)(0.toByte))
  for
    output <- ZStream.fromChunk(input)
                .via(myPipeline)
                .runCollect
  yield assertTrue(output.length == input.length)
}
```

## Manifest Construction

```scala
for
  start <- BlobOffset.either(0L)
  end   <- BlobOffset.either(1023L)
  span  <- Span.make(start, end)
  entry  = ManifestEntry(key, span, Map.empty)
  manifest <- Manifest.fromEntries(List(entry))
yield manifest
```

## Binary Attributes Builder

```scala
val attrs = BinaryAttributes.empty
  .advertiseSize(FileSize.unsafe(1024L))
  .advertiseMime(Mime.applyUnsafe("application/pdf"))
  .confirmSize(FileSize.unsafe(1024L))
```
