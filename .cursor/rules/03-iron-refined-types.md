# Iron Refined Types — Patterns & Standards

## Overview

Graviton uses [Iron 3.x](https://github.com/Iltotore/iron) for compile-time and runtime
refined types. Iron types are zero-overhead opaque aliases that enforce constraints at
the boundary and erase at runtime.

## Core Pattern: `RefinedTypeExt`

The project defines `RefinedTypeExt[A, C]` (in `graviton.core.types`) which extends
Iron's `RefinedType[A, C]` and additionally derives a `zio.schema.Schema[T]` so refined
types work seamlessly with zio-schema serialization.

```scala
// Canonical pattern for a new refined type:
type Mime = Mime.T
object Mime extends RefinedTypeExt[String, MinLength[1] & MaxLength[256]]
```

### When to use `RefinedTypeExt` vs `RefinedSubtypeExt`

- `RefinedTypeExt` — creates a fully opaque newtype. Values of the base type cannot be
  silently assigned. Use this for **domain identifiers** and **named quantities**.
- `RefinedSubtypeExt` — the refined type is a subtype of the base type. Use this when
  you need the value to be passable where the base type is expected without explicit
  unwrapping. Currently unused but available.

## Size Family Pattern: `SizeTrait`

For numeric bounded types (sizes, offsets, indexes), the project uses a powerful
`SizeTrait` hierarchy that provides:

- `Min`, `Max`, `Zero`, `One` constants
- `Integral[T]` and `DiscreteDomain[T]` instances (for `RangeSet` / `Span`)
- `unsafe(raw)` / `either(raw)` constructors
- `increment`, `checkedAdd`, `checkedSub`, `checkedMul` extensions
- `next` / `previous` for domain iteration

```scala
// 1-based size (positive int, max 16 MiB):
type BlockSize = BlockSize.T
object BlockSize extends SizeSubtype.Trait[1, 16777216, 0, 1]

// 0-based index (non-negative long):
type BlockIndex = BlockIndex.T
object BlockIndex extends IndexLong0
```

**Law**: Indexes are 0-based (min = 0). Sizes/counts are 1-based (min = 1).

## Block & UploadChunk — Collection-Level Refinement

For refining `Chunk[Byte]` (not just scalars), the project uses Iron's `refineEither`
on the collection directly:

```scala
type Block = Chunk[Byte] :| Block.Constraint
object Block:
  type Constraint = MinLength[1] & MaxLength[16777216]
  def fromChunk(chunk: Chunk[Byte]): Either[String, Block] =
    chunk.refineEither[Constraint]
  inline def unsafe(chunk: Chunk[Byte]): Block =
    chunk.asInstanceOf[Block]
```

## Opaque Type Pattern

For subtypes of existing refined types (e.g., `BlobOffset <: Offset`):

```scala
opaque type BlobOffset <: Offset = Offset
object BlobOffset:
  inline def unsafe(value: Long): BlobOffset = Offset.unsafe(value).asInstanceOf[BlobOffset]
  inline def either(value: Long): Either[String, BlobOffset] = Offset.either(value).map(_.asInstanceOf[BlobOffset])
  // Manually forward typeclass instances:
  given Ordering[BlobOffset] = summon[Ordering[Offset]].asInstanceOf[Ordering[BlobOffset]]
  given Integral[BlobOffset] = ...
  given DiscreteDomain[BlobOffset] = ...
  given Schema[BlobOffset] = ...
```

## Best Practices

1. **Refine at the boundary, trust inside**. All `unsafe` / `applyUnsafe` calls should
   happen only at module edges (config parsing, deserialization, CLI). Internal code passes
   refined types and trusts them.

2. **Use `either` for user-facing validation**. Never use `applyUnsafe` for user input.

3. **Derive ZIO Schema for all refined types**. The transparent `given` in `types.scala`
   auto-derives `Schema[IronType[A, B]]`; `RefinedTypeExt` provides it for newtypes.

4. **Compose constraints with `&` (intersection)**:
   ```scala
   type CustomAttributeName = ... extends RefinedTypeExt[String, IdentifierConstraint & MaxLength[64]]
   ```

5. **Keep constraint type aliases** for readability:
   ```scala
   type AlgoConstraint = Match["(sha-256|sha-1|blake3|md5)"]
   ```

6. **Test boundary values**. Every refined type should have tests exercising `Min`, `Max`,
   values just inside and just outside the bounds.

## Anti-Patterns to Avoid

- Do NOT use `asInstanceOf` to bypass refinement except in the `unsafe` constructor.
- Do NOT create refined types without a companion object providing `either` / `unsafe`.
- Do NOT mix Iron newtypes with plain type aliases — pick one.
- Do NOT add new numeric bounded types without going through `SizeTrait`.
