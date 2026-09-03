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
| `ContentAddressing.InteractiveBytes` | 0 to 8 KiB | Explicit bounded JVM/Scala.js content analysis utility |
| `BrowserFileAnalysis.BlockBytes` | 1 byte to 4 MiB | One CAS Playground block, never a complete file |
| `BrowserPdfTools.EditablePdfOutput` | 0 to 32 MiB | Explicit browser-only PDF rewrite result |

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

## Persisted manifests

Filesystem CAS writes incremental clean-store `GVM5` manifests. The header records bounded schema-versioned blob metadata, total size, block count, chunker identity, and optional proof version, key ID, Merkle root, and signature. Each following record contains one length-delimited block key, absolute offset, and length. The version-3 proof uses a version-1 Merkle B-tree with 64-entry leaves and 64-child internal nodes. Node summaries authenticate height, first index, entry count, start offset, end offset, and child digest. Readers reject invalid keys, non-contiguous offsets, length/key-size mismatches, unexpected entry counts, metadata or total-size drift, older envelopes, and trailing bytes. Authenticated readers verify the complete metadata-bound root before fetching a block. The executable ceiling is 1,048,576 entries.

The separate scodec `FramedManifest` and `FramedManifestRoot` version-1 codecs remain bounded explicit frame codecs, not repository manifest readers. Their defensive limits remain 16,384 entries per frame, 256 annotations per entry, 64 MiB per encoded frame, and 65,535 page references per root. Large `GVM5` repository manifests are reconstructed through streaming entry readers rather than converted to that in-memory frame model.

## Schema-driven metadata

The metadata subsystem uses ZIO Schema when a schema is known. `SchemaDef` couples a namespace, semantic version, schema ID, `Schema[A]`, and an explicit migration lookup. `NamespaceBlock` stores a `DynamicValue.Record` plus an optional schema header.

`DynamicJsonCodec` does not serialize an untyped `DynamicValue` directly. It first validates the value through the supplied `Schema[A]`, then uses ZIO Schema JSON. Decoding follows the reverse path and rejects a non-record result.

### Schema-driven diffs

For change reporting, convert both typed values through the same schema to `DynamicValue.Record`, then diff the records or their validated JSON representation. Keep provenance such as advertised versus confirmed attributes outside the generic diff so trust ordering is not erased.

There is no networked schema registry in the current runtime. `SchemaDef.migrateFrom` is an in-process, explicit migration boundary.

## Scala.js boundaries

The browser file lab is not a TypeScript hash reimplementation. `graviton-content-lab` streams `Blob` bytes through ZIO Streams, ZIO PDF structural scanning or FastCDC, incremental SHA-256, and the shared content-key renderer. `graviton-pdf-lab` owns the separately linked, 32 MiB bounded document rewrite. TypeScript coordinates browser files and renders returned metadata; it does not invent content IDs or chunk boundaries.

See [CAS Playground](../cas-playground.md), [Ranges and Boundaries](./ranges.md), and [Binary Streaming](../guide/binary-streaming.md).
