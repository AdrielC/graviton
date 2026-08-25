# Domain Types and Schema Boundaries

Graviton uses several type tools for different jobs. Iron enforces scalar and collection bounds, ZIO Schema describes selected wire and metadata values, and sealed or opaque Scala types prevent different key roles from being mixed accidentally. Not every internal value is serialized through ZIO Schema.

## Content keys

`KeyBits` is the validated cryptographic identity shared by the semantic key variants:

```scala
final case class KeyBits(algo: HashAlgo, digest: Digest, size: Long)

sealed trait BinaryKey:
  def bits: KeyBits

object BinaryKey:
  final case class Blob(bits: KeyBits)     extends BinaryKey
  final case class Block(bits: KeyBits)    extends BinaryKey
  final case class Chunk(bits: KeyBits)    extends BinaryKey
  final case class Manifest(bits: KeyBits) extends BinaryKey
```

The canonical text form is:

```text
<algorithm>:<lowercase-hex-digest>:<byte-length>
```

`graviton-shared` owns the syntax parser and renderer so the JVM backend and Scala.js content lab cannot drift. `KeyBits.fromString` then validates algorithm support and digest length. `BinaryKey.blob`, `block`, `chunk`, and `manifest` enforce role-specific size rules.

The runtime default is SHA-256. SHA-1 remains for legacy key compatibility. The `Blake3` enum and binary codec tag exist, but the default JCA-backed hasher requires an installed provider before that algorithm can execute. Graviton does not silently substitute another digest.

## Bounded byte types

Whole payloads remain streams. The few APIs that permit materialized bytes return a bound in the type:

| Type | Bound | Intended use |
| --- | ---: | --- |
| `UploadChunk` | 1 byte to 16 MiB | One validated upstream chunk |
| `Block` | 1 byte to 16 MiB | One canonical CAS block |
| `InMemoryBytes` | 0 to 16 MiB | Explicit small-blob convenience retrieval |
| `ControlPlaneBytes` | 0 to 1 MiB | JSON and error response decoding |
| `ContentAddressing.InteractiveBytes` | 0 to 8 KiB | JVM/Scala.js CAS Playground analysis |

Each collector reads at most its limit plus one byte, rejects overflow, and only then returns the Iron-refined result. Arbitrary blob ingestion, download, verification, and file copies use `ZStream` or a streaming sink.

## Refined primitives

`graviton.core.types` defines named refinements and supplies ZIO Schema transformations that revalidate decoded values. Important examples include:

| Type | Rule |
| --- | --- |
| `UploadChunkSize`, `BlockSize` | 1 through 16 MiB |
| `FileSize` | 1 byte through 1 TiB |
| `BlobOffset`, `Offset`, `BlockIndex` | Non-negative `Long` |
| `ManifestAnnotationKey` | Bounded stable identifier |
| `ManifestAnnotationValue` | At most 1,024 characters |
| `LocatorScheme`, `LocatorBucket`, `LocatorPath` | Bounded locator components |

Use `either` or a domain constructor at trust boundaries. `unsafe` constructors are reserved for call sites where a preceding invariant makes failure impossible.

## Ranges

`Span[A]` represents an inclusive non-empty range. Its smart constructor rejects a start greater than its end. `RangeSet[A]` stores normalized spans and provides union, intersection, difference, complement, and membership operations.

```scala
for
  first  <- Span.make(0L, 9L)
  second <- Span.make(10L, 19L)
yield RangeSet.single(first).add(second)
```

Adjacent spans merge when the selected `DiscreteDomain[A]` says they touch. Blob offsets use the non-negative refined `BlobOffset` domain.

## Persisted manifest frames

Filesystem manifests use explicit scodec frames rather than an inferred case-class layout. `FramedManifest` and `FramedManifestRoot` currently write version `1` and fail closed on unknown versions, malformed keys, invalid spans, duplicate annotation keys, trailing data, or bounds violations.

The defensive limits include:

- 16,384 entries per manifest frame
- 256 annotations per entry
- 64 MiB per encoded manifest or root frame
- 65,535 page references per manifest root

These are executable codec checks, not roadmap targets.

## Schema-driven metadata

The metadata subsystem uses ZIO Schema when a schema is known. `SchemaDef` couples a namespace, semantic version, schema ID, `Schema[A]`, and an explicit migration lookup. `NamespaceBlock` stores a `DynamicValue.Record` plus an optional schema header.

`DynamicJsonCodec` does not serialize an untyped `DynamicValue` directly. It first validates the value through the supplied `Schema[A]`, then uses ZIO Schema JSON. Decoding follows the reverse path and rejects a non-record result.

### Schema-driven diffs

For change reporting, convert both typed values through the same schema to `DynamicValue.Record`, then diff the records or their validated JSON representation. Keep provenance such as advertised versus confirmed attributes outside the generic diff so trust ordering is not erased.

There is no networked schema registry in the current runtime. `SchemaDef.migrateFrom` is an in-process, explicit migration boundary.

## Shared Scala.js contract

The browser content lab is not a TypeScript reimplementation. `graviton-shared` cross-compiles the refined interactive payload, SHA-256 digest text, fixed-block analyzer, duplicate detection, and content-key text format. JVM uses JCA and Scala.js uses Web Crypto behind the same shared tests.

See [CAS Playground](../cas-playground.md), [Ranges and Boundaries](./ranges.md), and [Binary Streaming](../guide/binary-streaming.md).
