# FreeScan V2 - Categorical Design (Volga-inspired)

## Overview

Redesigned FreeScan using proper category theory from Volga's Free Category library. This provides:

- **Feature-based hierarchy** (Pure → Stateful → Effectful → Full)
- **Type-level tracking** of I/O and State
- **Proper categorical structure** with automatic capability promotion
- **Single interpreter** for all feature levels
- **Cleaner composition** with >>>, &&&, |||

## Architecture Comparison

### Old Design (Arrow-Polymorphic)

```scala
trait Scan[Op[_, _], -I, +O]:
  type S  // Hidden state
  def stateSpec: StateF[S]
  def initF[F[_]]: F[S]
  def step: Op[(S, I), (S, Chunk[O])]
  def flush: Op[S, Chunk[O]]
```

**Problems:**
- Op is abstract, hard to reason about
- Joiner complexity for capability promotion
- InitF parametricity adds boilerplate
- State management is separate concern

### New Design (Free Categorical)

```scala
sealed trait FreeScan[U[_], +Q[_, _], F, In, Out, S]

// Constructors
case class Id[U[_], In]()
case class Embed[U[_], Q[_, _], F, In, Out, S](prim: Q[In, Out])
case class Sequential[...](first: FreeScan, second: FreeScan)
case class Fanout[...](left: FreeScan, right: FreeScan)
case class MapOut[...](base: FreeScan, f: Out1 => Out2)
```

**Benefits:**
- Free structure makes laws automatic
- Feature level F tracks capabilities at type-level
- State S is part of the type, composed via Tensor
- Single interpreter, feature dispatch at runtime
- Proper categorical operators

## Feature Hierarchy

```
Pure            -- Stateless transformations (arr, map)
  |
  ├─> Stateful  -- Accumulating state (fold, scan)
  |
  ├─> Effectful -- ZIO effects (no state)
  |
  └─> Full      -- Stateful + Effectful
```

Type-level union `F1 | F2` automatically computes LUB.

## Key Improvements

### 1. Automatic Capability Promotion

```scala
val pure: FreeScan[U, Q, Pure, Byte, Int, One] = ...
val stateful: FreeScan[U, Q, Stateful, Int, Long, Long] = ...

// Compose: Pure + Stateful = Stateful (automatic!)
val composed = pure >>> stateful
// Type: FreeScan[U, Q, Stateful, Byte, Long, Tensor[One, Long]]
```

No need for Joiner instances - Scala's type union does it!

### 2. State Composition via Tensor

```scala
val scan1: FreeScan[U, Q, Stateful, Byte, Int, Long]    // State = Long
val scan2: FreeScan[U, Q, Stateful, Byte, String, Boolean]  // State = Boolean

// Fanout: states compose
val combined = scan1 &&& scan2
// State: Tensor[Long, Boolean]
// Interpreter handles flattening
```

### 3. Single PrimScan Interface

```scala
trait PrimScan[In, Out, S]:
  def init: S
  def step(state: S, input: In): (S, Chunk[Out])
  def flush(state: S): Chunk[Out]
```

Simple, concrete, easy to implement. No Op, no F[_], no arrows.

### 4. Clean Composition Operators

```scala
// Sequential
val pipeline = hashBytes >>> countChunks >>> formatOutput

// Fanout (broadcast)
val metrics = countBytes &&& hashBlake3 &&& windowRate

// Product (parallel)
val parallel = processLeft *** processRight

// Choice (sum types)
val router = handleLeft ||| handleRight

// Profunctor
val adapted = scan.contramap(parse).map(format)
```

All type-safe, automatic state composition!

## Usage Examples

### Example 1: Byte Counter (Pure State)

```scala
// Define primitive
def counterPrim: PrimScan[Byte, Long, Long] =
  PrimScan.fold(0L)((s, _) => s + 1)

// Embed into FreeScan
given FreeU[Obj[Byte]] = FreeU.objInstance
given FreeU[Obj[Long]] = FreeU.objInstance
given FreeU[State[Long]] = FreeU.stateInstance

val counter = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](
  counterPrim
)

// Run
val (finalState, outputs) = PureInterpreter.run(
  counter,
  Iterable(1.toByte, 2.toByte, 3.toByte)
)([I, O, S] => (p: PrimScan[I, O, S]) => p)

// finalState = 3L
// outputs = Vector(1L, 2L, 3L)
```

### Example 2: Hash + Count (Composed)

```scala
val hashPrim: PrimScan[Byte, Array[Byte], HashState] = ...
val countPrim: PrimScan[Byte, Long, Long] = ...

val hash = FreeScan.embed(hashPrim)
val count = FreeScan.embed(countPrim)

// Fanout: run both on same input
val metrics = hash &&& count
// Type: FreeScan[U, PrimScan, Stateful, Byte, (Array[Byte], Long), Tensor[HashState, Long]]

// State is automatically composed!
```

### Example 3: CDC Pipeline (Sequential)

```scala
val chunkCDC: FreeScan[U, P, Stateful, Byte, Boundary, CDCState] = ...
val hashChunk: FreeScan[U, P, Stateful, Boundary, Digest, HashState] = ...
val countChunks: FreeScan[U, P, Stateful, Digest, Count, Long] = ...

// Sequential composition
val pipeline = chunkCDC >>> hashChunk >>> countChunks
// Type: FreeScan[U, P, Stateful, Byte, Count, Tensor[Tensor[CDCState, HashState], Long]]

// Interpreter flattens nested Tensor automatically
```

### Example 4: With Effects (ZIO)

```scala
// Effectful primitive (requires ZIO)
def lookupPrim[In, Out]: PrimScan[In, Out, Unit] = ...

val lookup = FreeScan.embed[U, P, Effectful, In, Out, Unit](lookupPrim)

// Compose with pure scan
val withEffect = counter >>> lookup
// Type: FreeScan[U, P, Full, Byte, Out, Tensor[Long, Unit]]
// Feature level promoted to Full automatically!
```

## Interpreter Strategy

The interpreter is **single**, dispatching on feature level:

```scala
object PureInterpreter extends ScanInterpreter[FreeU]:
  def run[Q[_, _], F, In, Out, S](
    scan: FreeScan[FreeU, Q, F, In, Out, S],
    inputs: Iterable[In]
  )(embedPrim: [I, O, SS] => Q[I, O] => PrimScan[I, O, SS]): (S, Vector[Out]) =
    scan match
      case Id() => ...
      case Embed(prim) => 
        val p = embedPrim(prim)
        // Run primitive directly
        ...
      case Sequential(f, g) =>
        val (s1, mid) = run(f, inputs)(embedPrim)
        val (s2, out) = run(g, mid)(embedPrim)
        ((s1, s2), out)  // State composed
      case Fanout(l, r) =>
        val (s1, o1) = run(l, inputs)(embedPrim)
        val (s2, o2) = run(r, inputs)(embedPrim)
        ((s1, s2), o1.zip(o2))  // States & outputs composed
      ...
```

Feature level F is erased at runtime - just a type-level tag!

## Benefits Over V1

| Aspect | V1 (Arrow-Polymorphic) | V2 (Free Categorical) |
|--------|------------------------|----------------------|
| Capability promotion | Manual Joiner instances | Automatic via type union |
| State composition | StateF + interpreters | Tensor at type level |
| Init handling | Parametric InitF[F[_]] | Concrete in PrimScan |
| Composition operators | Need Joiner everywhere | Clean >>>, &&&, ||| |
| Laws | Must verify manually | Free structure = automatic |
| Interpreter | Multiple (per Op) | Single (feature dispatch) |
| Type complexity | High (Op, F, InitF) | Medium (Free GADT) |
| Integration | New abstraction layer | Extends existing FreeScan |

## Migration from V1

V1 scans can be embedded as primitives:

```scala
// V1 scan
val oldScan: Scan[Op, I, O] = ...

// Convert to PrimScan
def toPrimScan[I, O, S](old: Scan.Aux[Op, I, O, S]): PrimScan[I, O, S] =
  new PrimScan[I, O, S]:
    def init = InitF.evaluate(old.init)
    def step(s, i) = 
      // Extract from Op[(S, I), (S, Chunk[O])]
      old.step.run((s, i))
    def flush(s) = old.flush.run(s)

// Embed into V2
val newScan = FreeScan.embed(toPrimScan(oldScan))
```

## ZIO Integration

For ZIO Streams, single channel interpreter:

```scala
object ZIOInterpreter:
  def toChannel[Q[_, _], F, In, Out, S](
    scan: FreeScan[FreeU, Q, F, In, Out, S]
  )(
    embedPrim: [I, O, SS] => Q[I, O] => PrimScan[I, O, SS]
  ): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[Out], S] =
    scan match
      case Id() => ZChannel.identity
      case Embed(prim) =>
        val p = embedPrim(prim)
        // Build channel with init/step/flush
        ...
      case Sequential(f, g) =>
        toChannel(f)(embedPrim) >>> toChannel(g)(embedPrim)
      case Fanout(l, r) =>
        // Broadcast channel
        ...
```

Feature level F determines runtime behavior (pure/effectful).

## Spark Integration

Pure scans compile to Iterator:

```scala
object SparkInterpreter:
  def toIterator[Q[_, _], In, Out, S](
    scan: FreeScan[FreeU, Q, Pure, In, Out, S]  // Only Pure!
  )(
    embedPrim: [I, O, SS] => Q[I, O] => PrimScan[I, O, SS]
  )(
    inputs: Iterator[In]
  ): Iterator[Out] =
    // Fold over iterator with state threading
    ...
```

Type system enforces Pure - won't compile for Effectful!

## Future Extensions

### 1. ArrowChoice (|||, +++)

Already present! Choice constructor handles Either routing.

### 2. Profunctor Laws

```scala
// Already supported
scan.contramap(f).contramap(g) == scan.contramap(f andThen g)
scan.map(f).map(g) == scan.map(f andThen g)
```

### 3. Optimization Pass

Pattern match Free structure for fusion:

```scala
def optimize[U[_], Q[_, _], F, In, Out, S](
  scan: FreeScan[U, Q, F, In, Out, S]
): FreeScan[U, Q, F, In, Out, S] =
  scan match
    // map fusion
    case MapOut(MapOut(base, f), g) => MapOut(base, f andThen g)
    // id elimination
    case Sequential(Id(), r) => r
    case Sequential(l, Id()) => l
    // contramap fusion
    case ContramapIn(ContramapIn(base, f), g) => ContramapIn(base, g andThen f)
    ...
```

### 4. Inspection/Introspection

Free structure is inspectable:

```scala
def extractPrimitives[U[_], Q[_, _], F, In, Out, S](
  scan: FreeScan[U, Q, F, In, Out, S]
): List[Q[?, ?]] =
  scan match
    case Embed(prim) => List(prim)
    case Sequential(f, g) => extractPrimitives(f) ++ extractPrimitives(g)
    case Fanout(l, r) => extractPrimitives(l) ++ extractPrimitives(r)
    ...
```

Useful for debugging, visualization, optimization analysis.

## Conclusion

FreeScan V2 using Volga's categorical design gives you:

✅ **Cleaner composition** - proper >>>, &&&, ||| operators  
✅ **Automatic promotion** - type unions handle capability LUB  
✅ **Type-level state** - Tensor composition, no runtime overhead  
✅ **Single interpreter** - feature dispatch, not Op-specific  
✅ **Free structure** - laws automatic, optimization easy  
✅ **Simpler primitives** - concrete PrimScan, no abstractions  

This is **architecturally superior** to the arrow-polymorphic V1 design while maintaining all the benefits (modularity, composition, optimization).

**Recommendation:** Replace existing FreeScan with V2 design. Existing scans can be embedded as PrimScan instances.
