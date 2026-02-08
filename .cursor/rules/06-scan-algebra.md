# Scan Algebra — Patterns & Standards

## Overview

Graviton has two complementary scan abstractions for stateful stream transduction:

1. **`Scan[I, O, S, C]`** — A direct, trait-based scan with type-level state and capabilities.
2. **`FreeScan[Q, A, B]`** — A free symmetric monoidal category over a primitive alphabet `Prim`.

Both compile down to `ZChannel` / `ZPipeline` via their respective `.toChannel` / `.toPipeline`.

## Scan Trait (graviton.core.scan.Scan)

```scala
trait Scan[-I, +O, S, +C]:
  def init(): S
  def step(state: S, input: I): (S, O)
  def flush(state: S): (S, Chunk[O])
```

- **S**: State type. `NoState` (= `Unit`) for stateless scans.
- **C**: Capability phantom type (like `FreeArrow`). `Any` = no capabilities.
- **Composition**: `>>>` (sequential), `&&&` (fanout/broadcast), `+++` (sum), `|||` (choice).
- **State composition**: `StateCompose` typeclass merges states (unit identity, pair fallback).
- **Labelled branches**: `ScanBranch[I, O, Label, S, C]` for named fanout fields.

### Combinators

| Combinator      | Type                              | Description                         |
|-----------------|-----------------------------------|-------------------------------------|
| `>>>`           | `Scan[I,M,...] >>> Scan[M,O,...]` | Sequential composition              |
| `&&&`           | `Scan[I,A,...] &&& Scan[I,B,...]` | Fanout (same input, paired output)  |
| `+++`           | Product of two independent scans  | Parallel on tupled input            |
| `\|\|\|`        | Sum: `Either[I,I2] => Either[O,O2]` | Choice routing                   |
| `map` / `contramap` | Output/input transformation  | Standard profunctor ops             |
| `first` / `second` | Lift to tuple positions        | Arrow-style                        |
| `labelled[L]`   | Wrap in `ScanBranch`              | Named branch for `&&&`             |

### Running

- `scan.runChunk(inputs)` — pure, in-memory execution
- `scan.toPipeline` — lift to `ZPipeline[Any, Nothing, I, O]`
- `scan.toChannel` — lift to `ZChannel`

## FreeScan (graviton.core.scan.FreeScanV2)

A reified (free) representation of scan pipelines that supports:

- **Optimization**: `Optimize.fuse` merges adjacent `Map1` and `Filter` nodes.
- **Compilation**: `Compile.toChannel` builds a mutable `Step` state machine.
- **Inspection**: Pattern-match on the tree for analysis / visualization.

### Primitive Alphabet (`Prim`)

```scala
sealed trait Prim[A, B]
  case Map1[A, B](f: A :=>: B)
  case Filter[A](p: A :=>: Boolean)
  case Flat[A, B](f: A :=>: Chunk[B])
  case Fold[A, B, S](init, step, flush)
```

### `SafeFunction` (Stack-Safe Composition)

`SafeFunction[-A, +B]` is a `Chunk[Any => Any]` — a stack-safe function composition
that avoids deep nested lambdas. Used internally by `Map1` fusion.

### Batteries-Included Scans (`FS.*`)

| Scan             | Type                                | Description                         |
|------------------|-------------------------------------|-------------------------------------|
| `FS.counter`     | `A => Long`                         | Monotonic element counter           |
| `FS.byteCounter` | `Chunk[Byte] => Long`               | Running byte total                  |
| `FS.hashBytes`   | `Chunk[Byte] => Either[String, Digest]` | Whole-stream hash             |
| `FS.buildManifest` | `ManifestEntry => Manifest`       | Accumulate manifest entries         |
| `FS.fixedChunker`  | `Chunk[Byte] => Chunk[Byte]`     | Fixed-size rechunking               |
| `FS.stateful`    | `(S, A) => S` with `S` output       | Generic stateful accumulator        |

### Tensor / Record-Based State

FreeScan uses `kyo.Record` for typed named fields in state and output:

```scala
type S = Record[("buffer" ~ Array[Byte]) & ("filled" ~ Int)]
```

Access fields with `state.buffer`, `state.filled`. The `asInstanceOf[S]` cast is safe
because `Record` is erased at runtime.

### IngestScan

`IngestScan.fastCdc` is a production-grade CDC scan built on `FS.fold` that emits
structured `Event` records with block bytes, digests, counters, and boundary reasons.

## Best Practices

1. **Use `Scan` for simple, direct transducers** where you control the state.
2. **Use `FreeScan` when you need optimization, inspection, or reified pipelines**.
3. **Always provide `flush`** — end-of-stream semantics matter for trailing data.
4. **Prefer `FS.fold` over raw `FreeScan.Embed(Fold(...))`** for readability.
5. **Test scan composition** by running `runChunk` on representative inputs.
6. **Label branches** when using `&&&` to produce named fields instead of bare tuples.
