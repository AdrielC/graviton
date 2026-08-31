# Plan: Adopt zio-blocks Typed Registers for Transducer Hot State

**Status**: Archived research proposal, not the production pipeline
**Author**: generated from codebase analysis  
**Date**: 2026-02-09

> Current reality: `RegisterIngestPipeline` implements individual register-backed stages for an ignored benchmark, but ordinary `Transducer.>>>` still composes nested hot-state tuples. The repository does not contain the flat-composition implementation proposed below, and the production CAS path does not invoke this benchmark pipeline. ZIO Blocks 0.0.51 also has an unreleased external opaque-wrapper layout fix, so broad schema/register migration is deferred. See [the current ZIO Blocks audit](./zio-blocks-audit.md).

::: warning Proposal, not runtime documentation
This document describes a possible register-backed pipeline. Graviton consumes released ZIO Blocks artifacts from Maven; there is no ZIO Blocks Git submodule in the current repository. None of the phase descriptions below should be read as implemented behavior.
:::

---

## 1. Problem Statement

Graviton's `Transducer` algebra currently uses two state representations:

1. **Hot state** (`type Hot`) — primitives, arrays, and tuples used in the per-element processing loop. This avoids per-step Record construction, but the current `step` contract still returns tuples and output Chunks and is not allocation-free. Examples: `Long`, `(Array[Byte], Int, Long)`, `(Either[String, Hasher], Long)`.

2. **Summary state** (`S`) — `kyo.Record` with named fields, constructed only at flush boundaries via `toSummary(h: Hot): S`. Relies on `asInstanceOf[Record[...]]` casts that are safe because `Record` is erased, but fragile if field names change.

The problems:

- **`kyo.Record` is not reliable on the supported Scala 3.8 line** — multi-field summaries containing `String` can throw `NoSuchElementException` during `selectDynamic`. The streaming transformation still works, and production CAS does not read the affected aggregate summary.
- **`asInstanceOf` casts are error-prone** — every `toSummary` method ends with `(Record.empty & ("f1" ~ v1) & ("f2" ~ v2)).asInstanceOf[S]`. If a field name or type representation drifts, the cast succeeds and later field access can fail at runtime.
- **The Kyo dependency surface is broader than the production need** — `graviton-core` pulls Kyo artifacts for `Record`, tags, and experimental scan interpreters even though the CAS data plane uses only one streaming transformation from this algebra.
- **Summary construction allocates** — building a `Record` at flush time creates a `Map[String, Any]` under the hood. This is acceptable today (flush is infrequent) but prevents using summaries in the hot path.

## 2. What zio-blocks Registers Offer

The `zio.blocks.schema.binding` package provides a **flat register file** abstraction:

| Type | Role |
|------|------|
| `Registers` | Mutable flat buffer: separate `Array[Byte]` (for all primitives via `ByteArrayAccess`) and `Array[AnyRef]` (for objects). No boxing for primitives. |
| `RegisterOffset` | Packed `Int` encoding byte-offset (upper 16 bits) and object-offset (lower 16 bits). Arithmetic via inline helpers. |
| `Register[A]` | Sealed ADT per primitive type (`Register.Long`, `Register.Int`, `Register.Double`, `Register.Object[A]`, etc.) with typed `get`/`set` methods. |
| `Constructor[A]` | Builds a value of type `A` from `Registers` at a given `RegisterOffset`. |
| `Deconstructor[A]` | Writes a value of type `A` into `Registers` at a given offset. |
| `RegisterType[A]` | Phantom ADT for compile-time primitive classification. |

Key properties:
- **Zero-boxing**: primitives live in a byte array accessed via `sun.misc.Unsafe` (JVM) or typed array views (JS/Native). No `java.lang.Long` wrapper ever.
- **Flat layout**: a composed register set is just `add(leftOffset, rightOffset)` — one `Int` addition. No nested tuples.
- **Platform-split**: `Registers` has JVM, JS, and Native implementations. The JVM version uses `Unsafe` for unaligned access; JS uses `DataView`.
- **Schema-derived**: `Constructor`/`Deconstructor` can be auto-derived from `zio.blocks.schema.Schema[A]` via the `Binding.Record` mechanism.
- **Current dependency**: the build resolves released `zio-blocks-*` 0.0.51 artifacts from Maven. No `modules/zio-blocks` submodule is present.

## 3. How Registers Map to Transducer Hot State

### Current: tuples of primitives

```scala
// IngestPipeline.rechunk
type Hot = (Array[Byte], Int, Long)  // buf, fill, blockCount

def step(h: Hot, chunk: Chunk[Byte]): (Hot, Chunk[Chunk[Byte]]) =
  val (buf, fill, count) = h
  // ...
  ((buf, fill, count), out)
```

### Proposed: typed register layout

```scala
// Register layout for rechunk stage
object RechunkRegisters:
  val buf        = Register.Object[Array[Byte]](0)
  val fill       = Register.Int(0)
  val blockCount = Register.Long(0)
  val offset     = RegisterOffset(objects = 1, ints = 1, longs = 1)

// In the transducer:
type Hot = Registers

def initHot: Registers = 
  val r = Registers(RechunkRegisters.offset)
  RechunkRegisters.buf.set(r, RegisterOffset.Zero, Array.ofDim[Byte](safeSize))
  RechunkRegisters.fill.set(r, RegisterOffset.Zero, 0)
  RechunkRegisters.blockCount.set(r, RegisterOffset.Zero, 0L)
  r

def step(h: Registers, chunk: Chunk[Byte]): (Registers, Chunk[Chunk[Byte]]) =
  val buf   = RechunkRegisters.buf.get(h, RegisterOffset.Zero)
  var fill  = RechunkRegisters.fill.get(h, RegisterOffset.Zero)
  var count = RechunkRegisters.blockCount.get(h, RegisterOffset.Zero)
  // ... same loop logic ...
  RechunkRegisters.fill.set(h, RegisterOffset.Zero, fill)
  RechunkRegisters.blockCount.set(h, RegisterOffset.Zero, count)
  (h, out)
```

### Composition: flat merging via RegisterOffset.add

When two transducers compose via `>>>`:

```scala
// Current: Hot = (self.Hot, that.Hot)  — nested tuples
// Proposed: Hot = Registers with combined offset

def andThen[...](that: Transducer[...]): Transducer[...] =
  new Transducer[...]:
    type Hot = Registers
    private val leftOffset  = self.registerLayout
    private val rightOffset = that.registerLayout
    private val totalOffset = RegisterOffset.add(leftOffset, rightOffset)
    
    def initHot: Registers =
      val r = Registers(totalOffset)
      self.initRegisters(r, RegisterOffset.Zero)
      that.initRegisters(r, leftOffset)
      r
    
    def step(h: Registers, i: I): (Registers, Chunk[O2]) =
      val (_, mids) = self.stepWithRegisters(h, RegisterOffset.Zero, i)
      // feed mids into that.stepWithRegisters(h, leftOffset, ...)
```

This gives **one flat `Registers` per composed pipeline** instead of nested `((Long, (Either[String, Hasher], Long)), (Array[Byte], Int, Long))` tuples.

## 4. Benefits

| Dimension | kyo.Record (current) | zio-blocks Registers (proposed) |
|-----------|---------------------|---------------------------------|
| **Scala version** | Compiles on 3.8.4, but some named summary reads fail | Proposed direction still requires proof on each supported Scala target |
| **Primitive boxing** | Hot path: none (tuples). Summary: boxed in Map | None anywhere — flat byte arrays |
| **Composition overhead** | Nested tuples of tuples | One flat `Registers` instance |
| **Field access safety** | `asInstanceOf` casts | Typed `Register[A].get/set` |
| **Dependencies** | kyo-data, kyo-core, kyo-prelude, kyo-zio | released zio-blocks-schema artifact |
| **Summary construction** | Allocates `Map[String, Any]` at flush | Read directly from registers — zero alloc |
| **Cross-platform** | JVM only (kyo) | JVM + JS + Native |
| **Schema integration** | None (manual field names) | Derive Constructor/Deconstructor from Schema |

## 5. Migration Plan

### Phase 1: Wire zio-blocks into the build

1. Continue using the pinned released `zio-blocks-schema` artifact. Do not introduce a source submodule merely for this experiment.
2. Add `zio-blocks-schema` as a dependency of `graviton-core`. Remove `kyo-data`, `kyo-core`, `kyo-prelude`, `kyo-zio` from `graviton-core` (they become test-only or removed entirely).
3. Verify compilation: `TESTCONTAINERS=0 ./sbt core/compile`.

**Risk**: `FreeScanV2`, `IngestScan`, `InterpretKyo`, `KyoScan`, `KyoParseScans`, `Rec.scala` all import `kyo.*`. These need migration or isolation.

**Mitigation**: Move Kyo-dependent scan code to a `graviton-kyo` bridge module (optional, off the critical path). The Transducer algebra has zero Kyo imports in its hot path — only `toSummary` uses `Record`.

### Phase 2: Define register layouts for existing transducers

For each transducer in `IngestPipeline` and `TransducerKit`, define a companion `object` with typed `Register` fields and a `RegisterOffset`:

| Transducer | Current Hot | Register layout |
|-----------|------------|----------------|
| `countBytes` | `Long` | `Register.Long(0)`, offset = `(longs=1)` |
| `hashBytes` | `(Either[String, Hasher], Long)` | `Register.Object[Hasher](0)`, `Register.Long(0)`, offset = `(objects=1, longs=1)` |
| `rechunk` | `(Array[Byte], Int, Long)` | `Register.Object[Array[Byte]](0)`, `Register.Int(0)`, `Register.Long(0)`, offset = `(objects=1, ints=1, longs=1)` |
| `blockCounter` | `Long` | `Register.Long(0)`, offset = `(longs=1)` |
| `dedup` | `(Set[K], Long, Long)` | `Register.Object[Set[K]](0)`, `Register.Long(0)`, `Register.Long(1)`, offset = `(objects=1, longs=2)` |
| `batch` | `(ChunkBuilder[A], Int, Long)` | `Register.Object[ChunkBuilder[A]](0)`, `Register.Int(0)`, `Register.Long(0)`, offset = `(objects=1, ints=1, longs=1)` |

### Phase 3: Replace `Hot` type with `Registers`

Introduce a new trait:

```scala
trait RegisterTransducer[-I, +O, S] extends Transducer[I, O, S]:
  type Hot = Registers
  
  /** The flat register layout for this stage. */
  def registerLayout: RegisterOffset
  
  /** Initialize registers at the given base offset. */
  def initRegisters(r: Registers, base: RegisterOffset): Unit
```

Implement for each transducer. The `step`/`flush` methods read/write via typed `Register` accessors instead of tuple destructuring.

### Phase 4: Flat composition

Replace the `>>>` implementation's `type Hot = (self.Hot, that.Hot)` with:

```scala
type Hot = Registers

private val splitOffset = self.registerLayout
val registerLayout      = RegisterOffset.add(self.registerLayout, that.registerLayout)
```

Both sides read/write the same `Registers` instance at different offsets. No tuple nesting. One flat buffer per pipeline.

### Phase 5: Replace `kyo.Record` summaries

Replace `toSummary` methods that return `Record[...]` with:

**Option A**: Return the `Registers` itself as the summary (zero-alloc, but less ergonomic).

**Option B**: Define summary case classes and derive `Constructor[Summary]` from `zio.blocks.schema.Schema[Summary]`:

```scala
case class IngestSummary(totalBytes: Long, digestHex: String, blockCount: Long, rechunkFill: Int)
object IngestSummary:
  given schema: zio.blocks.schema.Schema[IngestSummary] = zio.blocks.schema.Schema.derived
  
  val constructor: Constructor[IngestSummary] = schema.reflect.binding.constructor
```

Then `toSummary(h: Registers): IngestSummary = constructor.construct(h, RegisterOffset.Zero)`.

**Option C**: Keep `Record`-like named access via a thin wrapper around `Registers` that maps field names to `(RegisterOffset, Register[A])` pairs at construction time.

**Recommendation**: Option B (case classes) is the most practical. It gives named field access, plays well with `zio.blocks.schema.Schema`, and avoids the `kyo.Record` `selectDynamic` problems entirely. The `StateMerge` typeclass would merge case class types via a macro or manual instances.

### Phase 6: Migrate or isolate FreeScan / Kyo code

The `FreeScanV2`, `IngestScan`, `InterpretKyo`, `KyoScan`, `KyoParseScans`, and `Rec` modules all depend on `kyo.*`. Options:

1. **Migrate to zio-blocks**: Replace `kyo.Record`-based state in `FreeScanV2`'s `FS.fold` with `Registers`-based state. This is a larger effort because `FreeScanV2` uses `Record` in the reified `Prim.Fold` type.

2. **Isolate**: Move Kyo-dependent code to a `graviton-kyo` bridge module. Production CAS should retain only the independently tested streaming block-key transformation; the aggregate Transducer summary API is not the production orchestrator.

3. **Deprecate**: If the Transducer algebra fully subsumes `FreeScan`/`Scan` for production use, the older abstractions can be deprecated and eventually removed.

**Recommendation**: Option 2 first (isolate), then Option 3 over time as Transducers prove out.

### Phase 7: Remove kyo dependencies from graviton-core

Once all production code is on `Registers`:

1. Remove `kyo-data`, `kyo-core`, `kyo-prelude`, `kyo-zio` from `graviton-core/libraryDependencies`.
2. Unpin Scala from 3.7.x — test on 3.8+.
3. Drop the `graviton-kyo` bridge module if `FreeScan` is no longer used.

## 6. Verification

After each phase:

```bash
TESTCONTAINERS=0 ./sbt scalafmtAll compile test
```

Key test suites to verify:
- `ChunkerSpec` — chunker still produces correct blocks
- `TransducerSpec` (if exists) or the 257 passing tests — composition still works
- `IngestPipeline.countHashRechunk` — single-pass semantics preserved
- `CasBlobStoreSpec` — end-to-end ingest still works

## 7. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| zio-blocks API is pre-1.0 / unstable | Breaking changes upstream | Pin the released dependency and retain cross-platform contract tests |
| `RegisterOffset` overflow (16-bit limits) | Caps at 65535 bytes / 65535 objects per pipeline | Unlikely for ingest pipelines (< 100 bytes of state). Monitor. |
| `Registers` mutation complicates testing | Harder to reason about state | Keep pure `step` signature; `Registers` is an implementation detail |
| `Unsafe`-based `ByteArrayAccess` may not work on all JVMs | Runtime crashes on restricted JVMs | JVM 21+ has no module restrictions on `Unsafe`. JS/Native use safe fallbacks. |
| Migration is large surface area | Risk of regressions | Phase incrementally; keep old `Transducer` trait working during migration |

## 8. Summary

This proposal would replace the affected `kyo.Record` boundary with `zio.blocks.schema.binding.Registers` or schema-derived summary case classes. Any migration must benchmark the resulting allocations, verify the supported Scala and platform matrix, and make an explicit pre-1.0 compatibility decision. None of those outcomes is established by this archived plan.
