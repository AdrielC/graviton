# Scans & Events

Graviton's scan system provides composable, event-driven stream processing for binary content analysis.

## Overview

The `Scan` abstraction allows you to define transformations over byte streams that emit structured events:

```scala
trait Scan[+Event]:
  def feed(chunk: Chunk[Byte]): Scan[Event]
  def events: Chunk[Event]
  def complete: Scan[Event]
```

## Core Concepts

### Event-Driven Processing

Scans emit events as they process chunks:

```scala
enum ScanEvent:
  case LineDetected(line: String, offset: Long)
  case TokenFound(token: String, start: Long, end: Long)
  case BoundaryHit(offset: Long)
  case PatternMatch(pattern: String, offset: Long)

val scan: Scan[ScanEvent] = LineScan.initial

// Feed chunks incrementally
val scan1 = scan.feed(Chunk.fromArray("Hello\nWo".getBytes))
println(scan1.events) // LineDetected("Hello", 0)

val scan2 = scan1.feed(Chunk.fromArray("rld\n".getBytes))
println(scan2.events) // LineDetected("World", 6)
```

### Stateful Scanning

Scans maintain internal state across chunks:

```scala
final case class LineScan(
  buffer: Chunk[Byte],
  offset: Long,
  lines: Chunk[ScanEvent]
) extends Scan[ScanEvent]:
  
  def feed(chunk: Chunk[Byte]): LineScan =
    val combined = buffer ++ chunk
    val (complete, remaining) = combined.splitWhere(_ == '\n'.toByte)
    
    LineScan(
      buffer = remaining,
      offset = offset + complete.size,
      lines = lines :+ ScanEvent.LineDetected(
        String(complete.toArray),
        offset
      )
    )
```

## Scan Composition

### Sequential Composition

Chain scans to build pipelines:

```scala
val pipeline: Scan[Event] = 
  MimeDetectorScan.initial
    .andThen(EncodingDetectorScan.initial)
    .andThen(MetadataExtractorScan.initial)

// Process stream
val result = pipeline.feedAll(chunks)
```

### Parallel Composition

Run multiple scans simultaneously:

```scala
val combined: Scan[(Event1, Event2, Event3)] =
  Scan.zip3(
    HashScan.sha256,
    LineCountScan.initial,
    WordCountScan.initial
  )

// All scans see the same bytes
combined.feed(chunk)
```

### Branching

Fork scans based on detected content:

```scala
def selectScan(mimeType: String): Scan[Event] =
  mimeType match
    case "application/pdf" => PdfScan.initial
    case "text/plain" => TextScan.initial
    case "application/json" => JsonScan.initial
    case _ => NoOpScan

val dynamicScan: Scan[Event] = 
  MimeDetectorScan.initial.flatMap { mime =>
    selectScan(mime.contentType)
  }
```

## Built-in Scans

### Hash Scan

Compute cryptographic hashes:

```scala
object HashScan:
  def sha256: Scan[HashEvent] = ???
  def blake3: Scan[HashEvent] = ???
  def multi(algos: Seq[HashAlgo]): Scan[Seq[HashEvent]] = ???

enum HashEvent:
  case Incremental(algo: HashAlgo, bytesProcessed: Long)
  case Complete(algo: HashAlgo, digest: Digest)
```

### Anchor Scan

Detect content-defined boundaries:

```scala
object AnchorScan:
  def fastcdc(
    minSize: Int,
    avgSize: Int,
    maxSize: Int
  ): Scan[BoundaryEvent] = ???

enum BoundaryEvent:
  case ChunkBoundary(offset: Long, reason: BoundaryReason)
  case BlockComplete(span: Span, hash: Digest)

enum BoundaryReason:
  case ContentDefined  // Rolling hash hit target
  case MaxSizeReached  // Hit max chunk size
  case StreamEnd       // End of input
```

### Format Scan

Detect file formats and structure:

```scala
object FormatScan:
  def mimeDetector: Scan[MimeEvent] = ???
  def pdfStructure: Scan[PdfEvent] = ???
  def zipDirectory: Scan[ZipEvent] = ???

enum MimeEvent:
  case Detected(mimeType: String, confidence: Double)
  case EncodingDetected(encoding: String)
```

## ZIO Integration

### Stream Scanning

Convert scans to ZIO Streams:

```scala
import zio.stream.*

def scanStream[Event](
  scan: Scan[Event],
  stream: ZStream[Any, Throwable, Byte]
): ZStream[Any, Throwable, Event] =
  stream
    .chunks
    .mapAccum(scan) { (s, chunk) =>
      val next = s.feed(chunk)
      (next, next.events)
    }
    .flatMap(ZStream.fromChunk)

// Usage
val events = scanStream(
  HashScan.sha256,
  ZStream.fromFile(Path("large-file.bin"))
)
```

### Scan Sink

Create ZIO Sinks from scans:

```scala
def scanSink[Event](
  scan: Scan[Event]
): ZSink[Any, Nothing, Byte, Byte, Chunk[Event]] =
  ZSink.foldLeft(scan) { (s, byte) =>
    s.feed(Chunk.single(byte))
  }.map(_.complete.events)
```

## Advanced Patterns

### Content-Defined Chunking

Implement FastCDC with scans:

```scala
final case class FastCDCScan(
  minSize: Int,
  avgSize: Int,
  maxSize: Int,
  buffer: Chunk[Byte],
  hash: Long,
  chunks: Chunk[Span]
) extends Scan[BoundaryEvent]:
  
  def feed(chunk: Chunk[Byte]): FastCDCScan =
    val combined = buffer ++ chunk
    val (boundaries, remaining) = findBoundaries(combined)
    
    copy(
      buffer = remaining,
      chunks = chunks ++ boundaries
    )
  
  private def findBoundaries(bytes: Chunk[Byte]): (Chunk[Span], Chunk[Byte]) =
    // Rolling hash + boundary detection logic
    ???
```

### Multi-Format Detection

Detect and parse multiple formats:

```scala
val multiFormatScan: Scan[FormatEvent] = 
  Scan.fork(
    pdf = PdfScan.initial,
    zip = ZipScan.initial,
    tar = TarScan.initial
  ).dispatch { magic =>
    magic match
      case [0x25, 0x50, 0x44, 0x46] => "pdf"  // %PDF
      case [0x50, 0x4B, 0x03, 0x04] => "zip"  // PK..
      case _ => "unknown"
  }
```

### Buffered Lookahead

Scan with lookahead for context:

```scala
final case class LookaheadScan[Event](
  inner: Scan[Event],
  lookaheadSize: Int,
  buffer: Chunk[Byte]
) extends Scan[Event]:
  
  def feed(chunk: Chunk[Byte]): LookaheadScan[Event] =
    val combined = buffer ++ chunk
    if combined.size >= lookaheadSize then
      val (process, keep) = combined.splitAt(combined.size - lookaheadSize)
      LookaheadScan(
        inner = inner.feed(process),
        lookaheadSize = lookaheadSize,
        buffer = keep
      )
    else
      copy(buffer = combined)
```

## Performance Considerations

### Chunking Strategy

- **Small chunks** (4KB-64KB): Better for line-oriented scans
- **Large chunks** (1MB-4MB): Better for hash/CDC scans
- **Adaptive**: Adjust based on detected content type

### Memory Management

```scala
// Good: Process in streaming fashion
def streamingScan[Event](
  scan: Scan[Event],
  source: ZStream[Any, Throwable, Byte]
): ZStream[Any, Throwable, Event] =
  source.chunks.mapAccum(scan)((s, c) => (s.feed(c), s.events)).flatMap(ZStream.fromChunk)

// Bad: Buffer everything in memory
def bufferingScan[Event](
  scan: Scan[Event],
  bytes: Chunk[Byte]
): Chunk[Event] =
  scan.feed(bytes).complete.events  // Don't do this for large inputs!
```

### Parallelization

Run independent scans in parallel:

```scala
val parallelScans = ZIO.collectAllPar(
  scans.map { scan =>
    scanStream(scan, dataStream).runCollect
  }
)
```

## Testing Scans

### Property-Based Testing

```scala
test("scan is associative") {
  check(Gen.chunkOf(Gen.byte), Gen.chunkOf(Gen.byte)) { (chunk1, chunk2) =>
    val sequential = scan.feed(chunk1).feed(chunk2)
    val combined = scan.feed(chunk1 ++ chunk2)
    
    sequential.events == combined.events
  }
}
```

### Incremental Testing

```scala
test("scan produces same result regardless of chunking") {
  val data = randomBytes(1024 * 1024)
  
  val singleChunk = scan.feed(Chunk.fromArray(data)).complete
  val multiChunk = data.grouped(4096).foldLeft(scan)((s, c) => s.feed(Chunk.fromArray(c))).complete
  
  singleChunk.events == multiChunk.events
}
```

## See Also

- **[Schema & Types](./schema)** — Event type definitions
- **[Ranges & Boundaries](./ranges)** — Span and interval operations
- **[Chunking Strategies](../ingest/chunking)** — CDC algorithms

::: tip
Scans should be **pure** and **deterministic**. The same input bytes should always produce the same events.
:::
