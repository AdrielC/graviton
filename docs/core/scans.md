# Scans & Events

Graviton's scan system provides composable, type-safe stream processing. Today there are **two** related (but distinct) APIs:

- `graviton.core.scan.Scan` — a **direct, “sicko”** stateful transducer you can compose and run (including as a `ZPipeline`).
- `graviton.core.scan.FreeScanV2` — an **inspectable free** scan program used by existing chunkers/hashes/etc (see “FreeScanV2” sections below).

## Overview

A `Scan` is a stateful transducer from inputs `I` to outputs `O`, paired with typed state `S` and supporting:

- **Composable state**: state is threaded automatically through `>>>`, `&&&`, `+++`, `|||`, `first`, `second`, `dimap`
- **Ergonomic structured state**: you can model state as `kyo.Record[...]` (or use `Any` for “no state”)
- **Runnable**: interpret to `ZPipeline` with `scan.toPipeline`

## Core API (`graviton.core.scan.Scan`)

### Scan Interface

```scala
import zio.Chunk

trait Scan[-I, +O, S]:
  def init(): S
  def step(state: S, input: I): (S, O)
  def flush(state: S): (S, Chunk[O])
```

**Key properties:**
- **Empty state is `Any`**: stateless scans use `S = Any`.
- **State representation is user-defined**: `S` can be a case class, a `kyo.Record[...]`, a future `TypeMap`, etc.
- **Composition wraps state internally**: composing scans uses a carrier `ComposeState[SA, SB]`:
  - `Any` is an identity (`ComposeState[Any, SB] = SB`, `ComposeState[SA, Any] = SA`)
  - if both states are already `kyo.Record[...]`, composition uses record **intersection** (`Record[fa & fb]`)
  - otherwise it packs the two states into a record with internal labels `"_0"` and `"_1"` (implementation detail)

### Stateful Record Example

```scala
import graviton.core.scan.Scan
import kyo.Record
import kyo.Record.`~`
import zio.Chunk

type CountState = Record["count" ~ Long]

val counting: Scan[Long, Long, CountState] =
  Scan.fold[Long, Long, CountState]((Record.empty & ("count" ~ 0L)).asInstanceOf[CountState]) { (s, _) =>
    val next = s.count + 1
    val ns   = (Record.empty & ("count" ~ next)).asInstanceOf[CountState]
    (ns, next)
  }(s => (s, Chunk.empty))
```

## Stateless Scans

```scala
import graviton.core.scan.Scan

val doubled: Scan[Int, Int, Any] =
  Scan.pure(_ * 2)
```

## Running as a ZIO pipeline

```scala
import zio.stream.ZPipeline
import graviton.core.scan.Scan

val pipeline: ZPipeline[Any, Nothing, Int, Int] =
  Scan.pure[Int, Int](_ * 2).toPipeline
```

## Arrow Combinators

### Sequential Composition (`>>>`)

```scala
import graviton.core.scan.Scan

val pipeline =
  Scan.pure[Int, Int](_ + 1) >>> Scan.pure[Int, Int](_ * 2)
```

### Dimap (`dimap`)

```scala
import graviton.core.scan.Scan

val program =
  Scan.pure[Int, Int](_ * 2).dimap[String, String](_.toInt, _.toString)
```

### Parallel on tuples (`+++`)

```scala
import graviton.core.scan.Scan

val left  = Scan.pure[Int, Int](_ + 1)
val right = Scan.pure[String, Int](_.length)

val both = left +++ right
// (Int, String) => (Int, Int)
```

### Choice on either (`|||`)

```scala
import graviton.core.scan.Scan

val ints   = Scan.pure[Int, Int](_ + 1)
val chars  = Scan.pure[String, Int](_.length)
val routed = ints ||| chars
// Either[Int, String] => Either[Int, Int]
```

### Fanout / broadcast (`&&&`)

`&&&` runs both scans on the same input and returns a `kyo.Record` output (labelled `"_0"`/`"_1"` by default, or use `.labelled[...]`).

```scala
import graviton.core.scan.Scan
import kyo.Tag.given

val a = Scan.pure[Int, Int](_ + 1)
val b = Scan.pure[Int, String](_.toString)

val out = a &&& b
```

### First / Second

```scala
import graviton.core.scan.Scan

val scan = Scan.pure[Int, Int](_ + 1)

val first  = scan.first[String]  // (Int, String) => (Int, String)
val second = scan.second[String] // (String, Int) => (String, Int)
```

## Free Representation (FreeScanV2)

If you need an inspectable program (for optimization/visualization), see `graviton.core.scan.FreeScanV2` (`FreeScan`, `FS`, and the `Compile` interpreter). This is the API used by existing batteries-included scans (hashing, chunking, manifests, etc.).

**Benefits:**
- **Lawful**: Verify category, arrow, choice, parallel laws
- **Optimizable**: Fusion, dead-code elimination before interpretation
- **Inspectable**: Introspect scan structure for debugging/metrics
- **Multi-target**: Interpret to ZIO, pure loops, Spark, etc.

## Built-in FreeScanV2 primitives

`FreeScanV2` provides a small set of batteries-included primitives in `graviton.core.scan.FS` (see `FreeScanV2.scala`):

- `FS.counter` / `FS.byteCounter`
- `FS.hashBytes`
- `FS.fixedChunker`
- `FS.buildManifest`

Example:

```scala
import graviton.core.scan.FS.*
import kyo.Tag.given
import zio.Chunk

val program =
  counter[Chunk[Byte]].labelled["count"] &&&
    byteCounter.labelled["bytes"]
```

## Composition Examples

### Scan: sequential + state

```scala
import graviton.core.scan.Scan
import kyo.Record
import kyo.Record.`~`
import zio.Chunk

type CountState = Record["count" ~ Long]

val counting: Scan[Long, Long, CountState] =
  Scan.fold[Long, Long, CountState]((Record.empty & ("count" ~ 0L)).asInstanceOf[CountState]) { (s, _) =>
    val next = s.count + 1
    val ns   = (Record.empty & ("count" ~ next)).asInstanceOf[CountState]
    (ns, next)
  }(s => (s, Chunk.empty))

val program = Scan.pure[Long, Long](_ * 10) >>> counting
```

### FreeScanV2: broadcast with labelled record output

```scala
import graviton.core.scan.FS.*
import graviton.core.scan.Tensor
import kyo.Tag.given
import zio.Chunk

val scan =
  counter[Chunk[Byte]].labelled["count"] &&&
    byteCounter.labelled["bytes"]

val inputs = List(Chunk.fromArray("ab".getBytes()), Chunk.fromArray("c".getBytes()))
val out    = scan.runChunk(inputs).map(Tensor.toTuple["count", "bytes", Long, Long]).toList
```

## ZIO Integration
Both `Scan` and `FreeScanV2` have `toPipeline` / `toChannel` helpers for running on ZIO Streams.

## Capabilities (historical)

Earlier designs modeled “capabilities” via `F[_]`/`G[_]`. The current implementations (`Scan` and `FreeScanV2`) are concrete and are interpreted directly to runners like `ZPipeline`.

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

State is threaded linearly and must be treated as **local to a scan instance**: don’t share it between instances.
When state is modeled as `kyo.Record[...]`, composing record-states merges by **intersection**.

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

- **Stateful windows**: use `Scan.fold` with a `kyo.Record[...]` (or a domain case class) as the scan state.
- **Routing**: use `|||` (choice) for `Either`-based routing, or model richer routing with `FreeArrow` / `FreeScanV2`.
- **Metrics**: wrap a scan with `map/dimap` and record counters externally; internal “metered scan” helpers are still evolving.

## See Also

- **[Schema & Types](./schema.md)** — type-level programming
- **[Ranges & Boundaries](./ranges.md)** — Span operations
- **[Chunking Strategies](../ingest/chunking.md)** — CDC algorithms

::: tip
Scans are **pure** and **deterministic** — same inputs always produce same outputs. Use `FreeScan` for composition, interpret to `ZPipeline` for execution.
:::

::: warning
State `S` is **mutable within step**, **immutable across composition**. Don't share state between scan instances!
:::
