# Scans & Events

Graviton's scan system provides composable, type-safe stream processing through a BiKleisli-based arrow architecture with free representation and named-tuple state management.

## Overview

A `Scan` is an arrow that transforms inputs wrapped in `F[_]` into outputs wrapped in `G[_]`, paired with named state, supporting:

- **BiKleisli core**: `F[I] :=>: G[(S, O)]` where state `S` is a typed named-tuple record
- **Free representation**: Compose scans algebraically, compile to different targets (ZIO, pure loops, Spark)
- **Arrow combinators**: `>>>`, `dimap`, `|||`, `+++`, `first`, `second`, `fanout`, `split`, `merge`
- **Capability-based**: Pure → chunked → stateful → effectful upgrades via type classes
- **Law-abiding**: Category, Arrow, Choice, and Parallel laws verified via property tests

## Core Architecture

### BiKleisli

The fundamental building block:

```scala
final case class BiKleisli[F[_], G[_], I, O](run: F[I] => G[O])
```

Transforms `F[I]` into `G[O]`, where:
- `F[_]` = input capability (e.g., `Id`, `Chunk`, `ZIO`)
- `G[_]` = output capability (e.g., `Id`, `Chunk`, `ZIO`)
- `I` = input type
- `O` = output type (paired with state internally)

### Scan Interface

```scala
trait Scan[F[_], G[_], I, O]:
  type S <: Rec  // Named-tuple state (hidden by default)
  
  def init: InitF[S]  // Free-applicative initialization
  def step: BiKleisli[F, G, I, (S, O)]  // Transform step
  def flush(finalS: S): G[Option[O]]  // Finalization with explicit state
```

**Key properties:**
- State `S` is a **type member** (use `Scan.Aux[F,G,I,O,S]` to pin it)
- `init` is wrapped in a free-applicative for composable initialization
- `step` returns `(S, O)` — updated state paired with output
- `flush` explicitly receives the final state

## Named-Tuple State

State is represented as typed, labeled records with compile-time operations:

```scala
type Field[K <: String & Singleton, V] = (K, V)
type Rec = Tuple  // Tuple of Field[K, V]
type Ø = EmptyTuple  // Empty record

// Type-level operations
type ++[A <: Tuple, B <: Tuple] = ...  // Append, dropping EmptyTuple
type Get[A <: Rec, K] = ...  // Lookup by label
type Put[A <: Rec, F <: Field[?, ?]] = ...  // Insert/replace field
type Merge[A <: Rec, B <: Rec] = ...  // Merge records (right-biased)
```

**Example state:**

```scala
// Single field
type CountState = Field["count", Long] *: Ø

// Multiple fields
type CDCState = Field["roll", Long] *: Field["mask", Int] *: Ø

// Composed (no empty pollution)
type Combined = CountState ++ CDCState
// Result: Field["count", Long] *: Field["roll", Long] *: Field["mask", Int] *: Ø
```

### Accessing State

```scala
// Value-level operations
import graviton.core.scan.rec

val state = rec.field("count", 0L) *: EmptyTuple
val updated = rec.put(state, "count", 42L)
val value = get(updated, "count")  // 42L
```

## Pure Scans

### Stateless Transformation

```scala
import graviton.core.scan.*

// Pure function I => O
val doubled: Scan.Aux[Id, Id, Int, Int, Ø] =
  Scan.pure(i => i * 2)

// Chunked I => O (operates on Chunk[I])
val chunkedDoubled: Scan.Aux[Chunk, Chunk, Int, Int, Ø] =
  Scan.chunked(i => i * 2)
```

## Stateful Scans

### Adding State

```scala
object Stateful:
  def initKey[F[_], G[_], I, O, K <: String & Singleton, V, S0 <: Rec](
    base: Scan.Aux[F, G, I, O, S0],
    key: K,
    make: => V
  ): Scan.Aux[F, G, I, O, Put[S0, (K, V)]]
```

### Example: Byte Counter

```scala
def counting: Scan.Aux[Chunk, Chunk, Byte, Long, Field["count", Long] *: Ø] =
  new Scan[Chunk, Chunk, Byte, Long]:
    type S = Field["count", Long] *: Ø
    
    val init = InitF.pure(rec.field("count", 0L) *: EmptyTuple)
    
    val step = BiKleisli[Chunk, Chunk, Byte, (S, Long)] { cb =>
      var c = 0L
      cb.map { _ =>
        c += 1
        val state = rec.field("count", c) *: EmptyTuple
        (state, c)
      }
    }
    
    def flush(finalS: S) = 
      Chunk.single(get(finalS, "count"))  // Emit final count
```

## Arrow Combinators

### Sequential Composition (>>>)

Chain scans, concatenating state:

```scala
val pipeline: Scan.Aux[F, G, A, C, SA ++ SB] = 
  scanAB >>> scanBC

// Example: hash then count
val hashThenCount = HashScan.sha256 >>> counting
// State: Ø ++ Field["count", Long] = Field["count", Long] *: Ø
```

### Dimap (Pre/Post Mapping)

```scala
extension [F[_], G[_], I, O, S <: Rec](scan: Scan.Aux[F,G,I,O,S])
  def dimap[I2, O2](
    pre: I2 => I,      // Pre-process input
    post: O => O2      // Post-process output
  ): Scan.Aux[F, G, I2, O2, S]

// Example: parse then format
val stringProcessor = 
  intScan.dimap[String, String](
    _.toInt,        // String => Int
    _.toString      // Int => String
  )
```

### Parallel Composition (+++)

Process tuples in parallel, merge state:

```scala
extension [F[_], G[_], A, B, SA <: Rec](ab: Scan.Aux[F,G,A,B,SA])
  def +++[C, D, SB <: Rec](cd: Scan.Aux[F,G,C,D,SB])
  : Scan.Aux[F, G, (A,C), (B,D), Merge[SA,SB]]

// Example: hash and CDC in parallel
val parallel: Scan.Aux[Chunk, Chunk, (Byte, Byte), (Digest, Boundary), Merged] =
  HashScan.sha256 +++ CdcScan.fastCdc(4096, 8192, 16384)
```

### Choice (|||)

Process `Either` inputs:

```scala
extension [F[_], G[_], A, B, SA <: Rec](ab: Scan.Aux[F,G,A,B,SA])
  def |||[C, D, SB <: Rec](cd: Scan.Aux[F,G,C,D,SB])
  : Scan.Aux[F, G, Either[A,C], Either[B,D], Merge[SA,SB]]

// Example: route by type
val router = 
  textScan ||| binaryScan  // Either[Text, Binary] => Either[Parsed, Decoded]
```

### Fanout

Apply same input to both scans:

```scala
extension [F[_], G[_], A, B, SA <: Rec](ab: Scan.Aux[F,G,A,B,SA])
  def fanout[D, SB <: Rec](ad: Scan.Aux[F,G,A,D,SB])
  : Scan.Aux[F, G, A, (B,D), Merge[SA,SB]]

// Example: hash and count same bytes
val hashAndCount = HashScan.sha256.fanout(counting)
// Byte => (Digest, Long)
```

### First / Second

Transform one side of a tuple:

```scala
// Process first element, pass second through
scanAB.first[X]  // (A, X) => (B, X)

// Process second element, pass first through
scanAB.second[X]  // (X, A) => (X, B)
```

### Split & Merge

Route by predicate, then unify:

```scala
// Split by predicate
val split: Scan.Aux[F, G, A, Either[B,C], S] =
  scan.split(
    predicate = _ < threshold,
    right = alternateScan
  )

// Merge Either back to single type
val merged: Scan.Aux[F, G, Either[A,A], O, S] =
  scan.merge((x: B | C) => unify(x))
```

## Free Representation

Scans are compiled to a free algebra for optimization and interpretation:

```scala
enum FreeScan[F[_], G[_], I, O, S <: Rec]:
  case Prim(
    init: InitF[S],
    step: BiKleisli[F,G,I,(S,O)],
    flush: S => G[Option[O]]
  )
  
  case Seq[I, X, O, SA <: Rec, SB <: Rec](
    left:  FreeScan[F,G,I,X,SA],
    right: FreeScan[F,G,X,O,SB]
  ) extends FreeScan[F,G,I,O, SA ++ SB]
  
  case Dimap[I0, I1, O0, O1, S <: Rec](
    base: FreeScan[F,G,I1,O0,S],
    pre: I0 => I1,
    post: O0 => O1
  ) extends FreeScan[F,G,I0,O1,S]
  
  case Par[I1,I2,O1,O2,SA <: Rec, SB <: Rec](
    a: FreeScan[F,G,I1,O1,SA],
    b: FreeScan[F,G,I2,O2,SB]
  ) extends FreeScan[F,G,(I1,I2),(O1,O2), Merge[SA,SB]]
  
  case Choice[IL,IR,OL,OR,SL <: Rec, SR <: Rec](
    left: FreeScan[F,G,IL,OL,SL],
    right: FreeScan[F,G,IR,OR,SR]
  ) extends FreeScan[F,G,Either[IL,IR], Either[OL,OR], Merge[SL,SR]]
  
  case Fanout[I,O1,O2,SA <: Rec, SB <: Rec](
    a: FreeScan[F,G,I,O1,SA],
    b: FreeScan[F,G,I,O2,SB]
  ) extends FreeScan[F,G,I,(O1,O2), Merge[SA,SB]]
```

**Benefits:**
- **Lawful**: Verify category, arrow, choice, parallel laws
- **Optimizable**: Fusion, dead-code elimination before interpretation
- **Inspectable**: Introspect scan structure for debugging/metrics
- **Multi-target**: Interpret to ZIO, pure loops, Spark, etc.

## Built-in Scans

### Hash Scan

```scala
object HashScan:
  def sha256: FreeScan[Chunk, Chunk, Byte, Digest, Ø]
  def sha256Every(n: Int): FreeScan[Chunk, Chunk, Byte, Digest, Ø]
  def blake3: FreeScan[Chunk, Chunk, Byte, Digest, Ø]
  def multi(algos: Seq[HashAlgo]): FreeScan[Chunk, Chunk, Byte, Seq[Digest], Ø]
```

**Example:**

```scala
val hashPipeline = HashScan.sha256Every(64)
// Emits digest every 64 bytes, final digest on flush
```

### CDC Scan (Content-Defined Chunking)

```scala
object CdcScan:
  type SCDC = Field["roll", Long] *: Field["mask", Int] *: Ø
  
  def fastCdc(
    minSize: Int,
    avgSize: Int,
    maxSize: Int
  ): FreeScan[Chunk, Chunk, Byte, Int, SCDC]  // Emits boundary lengths
```

**State:**
- `"roll"`: Rolling hash accumulator
- `"mask"`: Target mask for average chunk size

**Example:**

```scala
val cdc = CdcScan.fastCdc(
  minSize = 4096,
  avgSize = 8192,
  maxSize = 16384
)
```

### Line Scan

```scala
object LineScan:
  type SLine = Field["buffer", Chunk[Byte]] *: Field["offset", Long] *: Ø
  
  def utf8: FreeScan[Chunk, Chunk, Byte, String, SLine]
```

**State:**
- `"buffer"`: Incomplete line bytes
- `"offset"`: Current byte offset

## Composition Examples

### Hash + CDC Pipeline

```scala
val pipeline: FreeScan[Chunk, Chunk, Byte, (Digest, Int), Merged] =
  HashScan.sha256.fanout(CdcScan.fastCdc(4096, 8192, 16384))

// Same bytes flow through both scans
// State = Merge[Ø, SCDC] = SCDC
```

### Sequential: Parse then Validate

```scala
val parseThenValidate = 
  JsonScan.parse >>> ValidationScan.schema(schema)

// JSON bytes => Parsed => Validated
// State = SJson ++ SValidation
```

### Classify by Size

```scala
val classify = 
  Scan.chunked[Int, Int](identity)
    .split(
      _ < 8192,  // Small
      Scan.chunked[Int, Int](identity)  // Large
    )

// Route small vs large to different paths
```

### Parallel Hash and Count

```scala
val hashAndCount = 
  HashScan.sha256 +++ counting

// Input: (Byte, Byte)
// Output: (Digest, Long)
// Each scan sees different input element
```

## ZIO Integration

### Interpretation to ZPipeline

```scala
import zio.stream.*
import graviton.streams.InterpretZIO

// Compile FreeScan to ZIO
val pipeline: ZPipeline[Any, Nothing, Byte, (Digest, Int)] =
  InterpretZIO.toPipeline(
    HashScan.sha256.fanout(CdcScan.fastCdc(4096, 8192, 16384))
  )

// Use in ZIO Stream
ZStream.fromFile(path)
  .via(pipeline)
  .foreach { case (digest, boundaryLen) =>
    Console.printLine(s"Digest: ${digest.hex}, Boundary: $boundaryLen")
  }
```

### Interpretation to ZChannel

For more control:

```scala
val channel: ZChannel[Any, Nothing, Chunk[Byte], Any, Nothing, Chunk[O]] =
  InterpretZIO.toChannel(freeScan)
```

## Capabilities

Scans are parametric in `F[_]` and `G[_]` via capability type classes:

```scala
trait Map1[F[_]]:
  def map[A,B](fa: F[A])(f: A => B): F[B]

trait Ap1[F[_]] extends Map1[F]:
  def pure[A](a: A): F[A]
  def ap[A,B](ff: F[A => B])(fa: F[A]): F[B]

// Pure (Identity)
type Id[A] = A
given Map1[Id] = ...

// Chunked
given Map1[Chunk] = ...

// Effectful (provided by interpreter)
// ZIO capabilities supplied at interpretation time
```

**Upgrade path:**
- `Scan[Id, Id, I, O]` — Pure, stateless
- `Scan[Chunk, Chunk, I, O]` — Batched
- `Scan[ZIO[R,E,*], ZIO[R,E,*], I, O]` — Effectful (via interpreter)

## Laws & Properties

All combinators satisfy:

### Category Laws

```scala
// Associativity
(f >>> g) >>> h ≡ f >>> (g >>> h)

// Identity
id >>> f ≡ f ≡ f >>> id
```

### Arrow Laws

```scala
// arr composition
arr(f) >>> arr(g) ≡ arr(g ∘ f)

// first commutation
first(f) >>> arr(swap) ≡ arr(swap) >>> second(f)
```

### Choice Laws

```scala
// Distribution
(f ||| g) >>> h ≡ (f >>> h) ||| (g >>> h)
```

### Parallel Laws

```scala
// Product
(f +++ g) >>> (h +++ k) ≡ (f >>> h) +++ (g >>> k)
```

### State Laws

```scala
// Append identity
S ++ Ø ≡ S ≡ Ø ++ S

// Merge commutativity (on disjoint keys)
Merge[{a: A}, {b: B}] ≡ {a: A, b: B}
```

## Testing

### Property-Based Testing

```scala
import zio.test.*

test("scan obeys category laws") {
  check(scanGen, scanGen, scanGen) { (f, g, h) =>
    val lhs = (f >>> g) >>> h
    val rhs = f >>> (g >>> h)
    
    assertTrue(runScan(lhs, inputs) == runScan(rhs, inputs))
  }
}

test("dimap identity") {
  check(scanGen) { scan =>
    val identity = scan.dimap(x => x)(x => x)
    assertTrue(runScan(identity, inputs) == runScan(scan, inputs))
  }
}
```

### State Consistency

```scala
test("state merges without empty pollution") {
  val merged: Scan.Aux[Id, Id, Int, Int, Field["a", Int] *: Field["b", Int] *: Ø] =
    scanA +++ scanB
  
  // No EmptyTuple in result type
  assertTrue(true)
}
```

## Performance

### Fusion

Free representation enables automatic fusion:

```scala
// Before optimization
val unfused = 
  scan1.dimap(f1)(g1) >>> 
  scan2.dimap(f2)(g2) >>> 
  scan3.dimap(f3)(g3)

// After fusion (interpreter optimizes)
// Single pass, no intermediate allocations
```

### State Management

- State is **mutable within scan step** for performance
- **Immutable across boundaries** (composition)
- No boxing for primitive fields (Long, Int, etc.)

### Memory

- **Bounded**: State size is compile-time known
- **Zero-copy**: Chunk-based scans avoid copying
- **Streaming**: Process arbitrarily large inputs

## Advanced Patterns

### Stateful Window

```scala
def slidingWindow[A](size: Int): FreeScan[Chunk, Chunk, A, Chunk[A], SWindow] =
  new Scan[Chunk, Chunk, A, Chunk[A]]:
    type S = Field["buffer", Chunk[A]] *: Ø
    val init = InitF.pure(rec.field("buffer", Chunk.empty[A]) *: EmptyTuple)
    val step = BiKleisli[Chunk, Chunk, A, (S, Chunk[A])] { ca =>
      var buf = Chunk.empty[A]
      ca.map { a =>
        buf = (buf :+ a).takeRight(size)
        val state = rec.field("buffer", buf) *: EmptyTuple
        (state, buf)
      }
    }
    def flush(finalS: S) = Chunk.empty
```

### Conditional Routing

```scala
def route[A, B, C](
  predicate: A => Boolean,
  left: FreeScan[F, G, A, B, SL],
  right: FreeScan[F, G, A, C, SR]
): FreeScan[F, G, A, Either[B,C], Merge[SL,SR]] =
  FreeScan.Choice(
    left.contramap(identity),
    right.contramap(identity)
  ).contramap(a => if predicate(a) then Left(a) else Right(a))
```

### Metered Scan

```scala
def metered[F[_], G[_], I, O, S <: Rec](
  base: Scan.Aux[F,G,I,O,S],
  metrics: MetricsRegistry
): Scan.Aux[F,G,I,O, Put[S, Field["count", Long]]] =
  Stateful.initKey(base, "count", 0L)
    .tapStep { (s, o) =>
      metrics.counter("scan.events").increment
      get(s, "count")
    }
```

## See Also

- **[Schema & Types](./schema)** — Type-level programming with named tuples
- **[Ranges & Boundaries](./ranges)** — Span operations
- **[Chunking Strategies](../ingest/chunking)** — CDC algorithms

::: tip
Scans are **pure** and **deterministic** — same inputs always produce same outputs. Use `FreeScan` for composition, interpret to `ZPipeline` for execution.
:::

::: warning
State `S` is **mutable within step**, **immutable across composition**. Don't share state between scan instances!
:::
