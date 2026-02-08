# Scala 3 Style Guide

## Language Level

- **Scala 3.7.4** — use Scala 3 syntax throughout: `enum`, `given`/`using`, `extension`,
  optional braces, `opaque type`, `inline`, `transparent inline`, union types, match types.
- **No Scala 2 idioms**: no `implicit` keyword, no `implicitly`, no procedure syntax, no
  `trait Foo { def bar }` (use `trait Foo: def bar`).

## Imports

- **Star imports** for packages: `import zio.*`, `import graviton.core.types.*`.
- Group imports: stdlib → ZIO/zio-* → Iron → scodec → project packages.
- Never import individual members when the whole package is commonly used.

## Formatting

- Run `scalafmtAll` before every commit.
- Indentation: 2 spaces (sbt default).
- Max line length: soft 120, hard 140.
- Optional-brace style (Scala 3 significant indentation) is the project convention.
- Use `end` markers for long blocks (>15 lines): `end match`, `end if`, `end SizeTrait`.

## Naming

- Types: `PascalCase` — `BlobStore`, `BlockSize`, `ManifestEntry`.
- Values/methods: `camelCase` — `putBlocks`, `streamBlob`, `maxBytes`.
- Constants: `PascalCase` for `val`s in companion objects: `val MaxBlockBytes`, `val Zero`.
- Type aliases: `PascalCase` — `type BlobOffset <: Offset`.
- Package objects: avoid; prefer top-level definitions.

## Error Handling

- **Never throw** unless absolutely necessary (e.g., JDK interop where `throw` is required).
- Use `Either[String, A]` for pure validation (core module).
- Use `ZIO[R, E, A]` with typed errors at the service boundary.
- Bridge with `ZIO.fromEither(...).mapError(...)` at module edges.
- Use sealed trait error hierarchies for domain errors (see `ChunkerCore.Err`).

## Type Annotations

- Explicit return types on all public methods and vals.
- Explicit types on `given` instances.
- Inferred types are fine for local `val`/`var` in method bodies.

## Traits & Classes

- Prefer `trait` for service abstractions (`BlockStore`, `BlobStore`, `MetricsRegistry`).
- Use `final case class` for data; `final class` for stateful service implementations.
- Use `sealed trait` + `enum` for closed hierarchies.
- Use `derives CanEqual` on sealed hierarchies that need equality.

## Comments & Documentation

- Scaladoc (`/** ... */`) on all public types and methods.
- Use `@param` only when the name isn't self-explanatory.
- Inline comments (`//`) for non-obvious logic; prefer self-documenting names.
- Mark temporary workarounds with `// HACK:` or `// FIXME:`.
