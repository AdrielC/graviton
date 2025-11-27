# Chunking Strategies

Graviton supports multiple chunking algorithms for content-defined deduplication. Choosing the right strategy impacts storage efficiency and retrieval performance.

## Overview

Chunking divides byte streams into addressable blocks. Graviton supports:

- **Fixed-size chunking** — Simple, predictable boundaries
- **FastCDC** — Fast content-defined chunking
- **BuzHash CDC** — Classic rolling hash approach
- **Rabin fingerprinting** — High-quality boundaries

### Typed Blocks & Upload Chunks

All chunkers in Graviton emit opaque `Block` values (backed by `Chunk[Byte]`) so that size invariants are enforced at the type level.

```scala
import graviton.core.attributes.{BinaryAttributes, Source, Tracked}
import graviton.core.model.{Block, BlockBuilder, ByteConstraints}
import zio.Chunk

val bytes: Chunk[Byte]   = Chunk.fromArray(inputArray)
val blocks: Chunk[Block] = BlockBuilder.chunkify(bytes)

// Strongly typed helpers
val firstBlockSize = blocks.headOption.map(_.blockSize) // -> ByteConstraints.BlockSize

val updatedAttributes = blocks.headOption
  .fold(BinaryAttributes.empty) { block =>
    BinaryAttributes.empty.confirmSize(Tracked.now(block.fileSize, Source.Derived))
  }
```

The same module exposes `ByteConstraints.refine*` helpers for validating raw sizes before constructing blocks or upload chunks. Downstream APIs continue to import `graviton.core.types.BlockSize` / `ChunkCount` as before, but now benefit from these refined guarantees.

## Fixed-Size Chunking

### Algorithm

Split every N bytes:

```scala
object FixedChunker:
  def apply(chunkSize: Int): ZPipeline[Any, Nothing, Byte, Chunk[Byte]] =
    ZPipeline.groupedWithin(chunkSize, Duration.Infinity)

// Usage
ZStream.fromFile(path)
  .via(FixedChunker(1024 * 1024))  // 1MB chunks
  .foreach(chunk => storeBlock(chunk))
```

### Characteristics

| Property | Value |
|----------|-------|
| Speed | Fastest |
| Deduplication | Poor (boundary-sensitive) |
| Predictability | Excellent |
| Best for | Append-only logs, fixed-record data |

### When to Use

✅ **Good for:**
- Append-only data (logs, time-series)
- When deduplication isn't important
- Predictable I/O patterns matter

❌ **Avoid for:**
- Documents with edits in the middle
- Data with insertions/deletions
- Maximum deduplication needed

## FastCDC

### Algorithm

FastCDC uses a gear hash with jump tables for high performance:

```scala
final case class FastCDCConfig(
  minSize: Int = 256 * 1024,      // 256 KB
  avgSize: Int = 1024 * 1024,     // 1 MB
  maxSize: Int = 4 * 1024 * 1024, // 4 MB
  normalization: Int = 2
)

object FastCDC:
  def chunker(config: FastCDCConfig): ZPipeline[Any, Nothing, Byte, Block] =
    ZPipeline.suspend {
      ZPipeline.mapAccumChunks(State.initial(config)) { (state, chunk) =>
        val (newState, blocks) = state.feed(chunk)
        (newState, Chunk.fromIterable(blocks))
      }
    }
```

### Normalization Levels

FastCDC supports multiple normalization levels:

```scala
enum NormalizationLevel:
  case Level0  // No normalization (fastest)
  case Level1  // One level (balanced)
  case Level2  // Two levels (better dedup)
  case Level3  // Three levels (maximum dedup, slower)

def computeMask(level: NormalizationLevel, avgSize: Int): Long =
  level match
    case Level0 => maskFromSize(avgSize)
    case Level1 => maskFromSize(avgSize) | maskFromSize(avgSize / 2)
    case Level2 => maskFromSize(avgSize) | maskFromSize(avgSize / 2) | maskFromSize(avgSize / 4)
    case Level3 => // Complex multi-level mask
```

### Characteristics

| Property | Value |
|----------|-------|
| Speed | Fast (2-3 GB/s) |
| Deduplication | Very Good |
| Chunk size variance | Low |
| Best for | General-purpose storage |

### Configuration Guide

```scala
// High throughput, lower dedup
val highSpeed = FastCDCConfig(
  minSize = 512 * 1024,
  avgSize = 2 * 1024 * 1024,
  maxSize = 8 * 1024 * 1024,
  normalization = 1
)

// Balanced
val balanced = FastCDCConfig(
  minSize = 256 * 1024,
  avgSize = 1024 * 1024,
  maxSize = 4 * 1024 * 1024,
  normalization = 2
)

// Maximum deduplication
val maxDedup = FastCDCConfig(
  minSize = 128 * 1024,
  avgSize = 512 * 1024,
  maxSize = 2 * 1024 * 1024,
  normalization = 3
)
```

## Anchored CDC

### Algorithm

Uses content-defined anchors with rechunking:

```scala
final case class AnchoredCDCConfig(
  anchorPattern: ByteString,     // Pattern to search for
  minSize: Int = 256 * 1024,
  avgSize: Int = 1024 * 1024,
  maxSize: Int = 4 * 1024 * 1024,
  rechunkThreshold: Double = 0.8  // Rechunk if chunk > threshold * maxSize
)

object AnchoredCDC:
  def chunker(config: AnchoredCDCConfig): ZPipeline[Any, Nothing, Byte, Block] =
    ZPipeline.fromSink(
      ZSink.foldWeightedDecompose(Chunk.empty[Byte])(_.size.toLong)(config.maxSize.toLong) {
        case (acc, chunk) =>
          val combined = acc ++ chunk
          val anchors = findAnchors(combined, config.anchorPattern)
          
          if anchors.nonEmpty then
            // Split at anchor points
            val (blocks, remaining) = splitAt Anchors(combined, anchors)
            (blocks, remaining)
          else if combined.size >= config.maxSize then
            // Force split at max size
            (Chunk.single(combined), Chunk.empty)
          else
            // Keep accumulating
            (Chunk.empty, combined)
      }
    )
```

### Use Cases

**Document formats:**
```scala
// PDF: Split at object boundaries
val pdfAnchors = AnchoredCDCConfig(
  anchorPattern = ByteString("endobj\n")
)

// ZIP: Split at file entries
val zipAnchors = AnchoredCDCConfig(
  anchorPattern = ByteString(0x50, 0x4B, 0x03, 0x04)  // PK.. signature
)
```

## BuzHash CDC

### Algorithm

Classic rolling hash with simpler implementation:

```scala
final case class BuzHashConfig(
  minSize: Int = 256 * 1024,
  avgSize: Int = 1024 * 1024,
  maxSize: Int = 4 * 1024 * 1024,
  windowSize: Int = 64
)

object BuzHash:
  def chunker(config: BuzHashConfig): ZPipeline[Any, Nothing, Byte, Block] =
    val mask = maskFromAvgSize(config.avgSize)
    
    ZPipeline.mapAccumChunks(State.initial(config)) { (state, chunk) =>
      chunk.foldLeft((state, Chunk.empty[Block])) { case ((s, blocks), byte) =>
        val hash = s.hash.roll(byte)
        
        if s.size >= config.minSize && (hash & mask) == 0 then
          // Boundary hit
          val block = Block(s.buffer :+ byte, hash)
          (State.initial(config), blocks :+ block)
        else if s.size >= config.maxSize then
          // Force boundary
          val block = Block(s.buffer :+ byte, hash)
          (State.initial(config), blocks :+ block)
        else
          // Keep accumulating
          (s.copy(buffer = s.buffer :+ byte, hash = hash, size = s.size + 1), blocks)
      }
    }
```

### Characteristics

| Property | Value |
|----------|-------|
| Speed | Moderate (1-2 GB/s) |
| Deduplication | Good |
| Chunk size variance | Moderate |
| Best for | Legacy compatibility |

## Comparison Matrix

| Algorithm | Speed | Dedup Quality | Variance | Memory |
|-----------|-------|---------------|----------|--------|
| Fixed | ⭐⭐⭐⭐⭐ | ⭐ | None | Minimal |
| FastCDC | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | Low | Low |
| Anchored | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Medium | Medium |
| BuzHash | ⭐⭐⭐ | ⭐⭐⭐ | Medium | Low |
| Rabin | ⭐⭐ | ⭐⭐⭐⭐⭐ | High | Medium |

## Choosing an Algorithm

### Decision Tree

```mermaid
flowchart TD
    classDef decision fill:#fef3c7,stroke:#d97706,color:#78350f;
    classDef action fill:#e0f2fe,stroke:#0369a1,color:#0c4a6e;

    need((Need chunking?)):::decision
    direct[Stream directly]:::action
    dedup{Need deduplication?}:::decision
    format{Format-aware anchors available?}:::decision
    priority{Priority?}:::decision

    fixed[Fixed-size]:::action
    anchored[Anchored CDC]:::action
    fast1[FastCDC (speed)]:::action
    fast2[FastCDC (balanced)]:::action
    fast3[FastCDC (dedup)]:::action

    need -->|No| direct
    need -->|Yes| dedup
    dedup -->|No| fixed
    dedup -->|Yes| format
    format -->|Yes| anchored
    format -->|No| priority
    priority -->|Speed| fast1
    priority -->|Balanced| fast2
    priority -->|Dedup| fast3
```

### Recommendations

**General-purpose:**
```scala
val chunker = FastCDC.chunker(FastCDCConfig(
  minSize = 256.KB,
  avgSize = 1.MB,
  maxSize = 4.MB,
  normalization = 2
))
```

**High-throughput logs:**
```scala
val chunker = FixedChunker(1.MB)
```

**Structured documents:**
```scala
val chunker = AnchoredCDC.chunker(AnchoredCDCConfig(
  anchorPattern = detectFormat(stream),
  minSize = 128.KB,
  avgSize = 512.KB,
  maxSize = 2.MB
))
```

**Maximum deduplication:**
```scala
val chunker = FastCDC.chunker(FastCDCConfig(
  minSize = 64.KB,
  avgSize = 256.KB,
  maxSize = 1.MB,
  normalization = 3
))
```

## Advanced Patterns

### Adaptive Chunking

Switch algorithms based on detected content:

```scala
def adaptiveChunker: ZPipeline[Any, Throwable, Byte, Block] =
  ZPipeline.fromSink(
    for {
      header <- ZSink.take(8192)  // Sample first 8KB
      format <- detectFormat(header)
      chunker = format match
        case Format.PDF => AnchoredCDC.chunker(pdfConfig)
        case Format.ZIP => AnchoredCDC.chunker(zipConfig)
        case Format.Log => FixedChunker(1.MB)
        case _ => FastCDC.chunker(defaultConfig)
    } yield chunker
  )
```

### Two-Level Chunking

Chunk, then sub-chunk for better dedup:

```scala
val twoLevel = 
  FastCDC.chunker(FastCDCConfig(avgSize = 4.MB))  // Coarse
    .via(ZPipeline.mapChunks { chunk =>
      // Sub-chunk each large block
      chunk.flatMap { block =>
        if block.size > 1.MB then
          rechunk(block, FastCDCConfig(avgSize = 256.KB))
        else
          Chunk.single(block)
      }
    })
```

### Dedup-aware Compression

Only compress non-deduped blocks:

```scala
def smartCompression(
  chunker: ZPipeline[Any, Nothing, Byte, Block],
  dedupIndex: DedupIndex
): ZPipeline[Any, Throwable, Byte, StorableBlock] =
  chunker
    .mapZIO { block =>
      for {
        hash <- HashAlgo.SHA256.hash(block.bytes)
        exists <- dedupIndex.contains(hash)
        final <- if exists then
          ZIO.succeed(StorableBlock.Ref(hash))  // Already stored
        else
          compress(block).map(StorableBlock.Compressed(_))  // New block
      } yield final
    }
```

## Performance Tuning

### Chunk Size Impact

**Small chunks (64-256 KB):**
- ✅ Better deduplication
- ✅ Finer-grained retrieval
- ❌ More metadata overhead
- ❌ More index lookups

**Large chunks (2-8 MB):**
- ✅ Less metadata
- ✅ Faster streaming
- ❌ Worse deduplication
- ❌ Larger retrieval granularity

### Parallelization

Chunk multiple streams concurrently:

```scala
val parallelChunking = ZStream.fromIterable(files)
  .mapZIOPar(8) { file =>
    ZStream.fromFile(file)
      .via(FastCDC.chunker(config))
      .foreach(block => store.put(block))
  }
```

### Memory Management

Use bounded queues:

```scala
def boundedChunking(
  stream: ZStream[Any, Throwable, Byte],
  chunker: ZPipeline[Any, Nothing, Byte, Block],
  maxQueued: Int = 16
): ZStream[Any, Throwable, Block] =
  stream
    .via(chunker)
    .buffer(maxQueued)
```

## Testing

### Property Tests

```scala
test("chunk boundaries are content-defined") {
  check(Gen.chunkOf(Gen.byte)) { bytes =>
    val chunks1 = chunkBytes(bytes)
    val chunks2 = chunkBytes(bytes.take(100) ++ bytes.drop(100))
    
    // Boundaries after byte 100 should be identical
    chunks1.drop(1) == chunks2.drop(1)
  }
}

test("chunk sizes respect bounds") {
  check(Gen.chunkOf(Gen.byte)) { bytes =>
    val chunks = chunkBytes(bytes, config)
    
    chunks.forall { chunk =>
      chunk.size >= config.minSize && chunk.size <= config.maxSize
    }
  }
}
```

## See Also

- **[Scans & Events](../core/scans.md)** — Boundary detection
- **[End-to-end Upload](../end-to-end-upload.md)** — Complete ingest pipeline
- **[Performance Tuning](../ops/performance.md)** — Optimization strategies

::: tip
Start with FastCDC Level2 for most use cases. Profile with your actual data before optimizing!
:::
