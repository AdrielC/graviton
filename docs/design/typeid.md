# Principled `TypeId` system (replacing `TypeName`)

## Status

**Status:** Accepted  
**Created:** 2025-12-28  
**Updated:** 2025-12-28

## What we are building

We are replacing `TypeName` with a real, principled `TypeId` system that:

- works in Scala 2.13 and Scala 3.5+
- correctly models types vs. type constructors
- supports arbitrary arity in Scala 3
- does not leak Scala 3 concepts into shared code
- has zero runtime overhead
- is robust against implicit ambiguity and inference disasters
- is extensible to aliases, opaques, structural types later

This is **not** zio-schema `TypeId`. That approach was a dead end. This is closer to kyo + shapeless tagging, but stricter and cleaner.

## Core idea (mental model)

A `TypeId` is:

- a runtime record (`TypeIdRepr`)
- tagged with a phantom type that encodes what kind of thing it is

The phantom tag:

- carries arity information
- is erased at runtime
- is selected by macros
- is never written by users

Users ask for `derive[A]`. The system figures out what “lane” `A` belongs to and returns the right tagged value.

## Runtime representation (single, boring, correct)

```scala
final case class TypeIdRepr(
  owner: Owner,
  name: String,
  params: List[TypeParam]
)
```

That’s it. Everything else is phantom typing.

## Tagging mechanism (shapeless-style, but tighter)

```scala
trait Tagged[+U] extends Any
type @@[+T, +U] = T with Tagged[U]
```

- `Tagged` is covariant
- `@@` is covariant in both parameters
- no runtime allocation
- no reflection
- no hacks

A `TypeId` is:

```scala
type Id[+K <: KindTag] = TypeIdRepr @@ K
```

## Arity is modeled as a hierarchy (important)

Arity is **not** encoded in the runtime value.  
Arity is **not** an enum.  
Arity is **not** a type parameter on `TypeId`.

Arity lives entirely in phantom tags:

```scala
sealed trait KindTag

sealed trait K0[A]       extends KindTag   // normal types
sealed trait K1[F[_]]    extends KindTag   // unary type constructors
sealed trait K2[F[_, _]] extends KindTag   // binary type constructors

// overflow / AnyKind lane
sealed trait KN[A <: AnyK] extends KindTag
```

This gives us:

- a clean hierarchy
- precise typing
- no accidental unification
- no inference loops

## The `AnyK` abstraction (version-specific)

We introduce one abstract type, version-specific:

```scala
trait TVersionSpecific {
  type AnyK
}
```

- Scala 3: `type AnyK = scala.AnyKind`
- Scala 2: `type AnyK = Any`

Crucially:

- `AnyK` is not sealed
- `AnyK` is not a trait
- shared code never assumes what it is

Only `KN` is constrained by `AnyK`. Everything else stays unconstrained to keep Scala 2 happy.

## Derivation model (key win)

Users never specify arity. They just call:

```scala
TypeId.derive[A]
```

Under the hood:

```scala
trait Tagger[A] {
  type Out <: KindTag
  def derive: Id[Out]
}

def derive[A <: AnyK](implicit t: Tagger[A]): Id[t.Out] =
  t.derive
```

Why this works well:

- the macro picks `Out`
- the return type is precise
- callers don’t see arity
- overload resolution is stable
- no tagging gymnastics at call sites

## Scala 2 strategy

Scala 2 cannot talk about `AnyKind`, so we don’t try.

Instead:

- provide multiple `Tagger` instances
- select via implicit priority

Low priority:

- `Tagger[A]` → `K0[A]`

Higher priority:

- `Tagger[F[_]]` → `K1[F]`
- `Tagger[F[_, _]]` → `K2[F]`

Each is macro-derived.

Result:

- no ambiguity
- no diverging implicit expansion
- no fake higher-kinded encodings
- `KN` is simply not produced in Scala 2

## Scala 3 strategy

Scala 3 gets the nicer version:

- single inline macro: `Tagger[A <: AnyK]`
- macro inspects arity
- returns:
  - `K0` for arity 0
  - `K1` for arity 1
  - `K2` for arity 2
  - `KN[A]` for arity ≥ 3

No overloads. No priority hacks. Just one `derive`.

## Why this design works (punchline)

- ✔ no sealed `AnyK`
- ✔ no `TypeName` partial modeling
- ✔ no runtime cost
- ✔ no Scala 3 bleed into shared code
- ✔ no implicit hell
- ✔ clean migration path
- ✔ future-proof for aliases, opaques, structural types

This is:

- simpler than zio-schema’s approach
- stricter than shapeless’ tagging
- more expressive than `TypeName`
- implementable without compiler tears

## Next steps

Design is done; implementation is mechanical:

- implement macros
- refactor callers
- delete `TypeName`
- ship

