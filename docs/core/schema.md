# Schema & Types

Graviton uses **zio-schema** for all data modeling, providing type-safe serialization, evolution, and derivation.

## Core Type System

### Opaque Types

Graviton extensively uses opaque types for type safety:

```scala
opaque type BinaryKey = Array[Byte]
opaque type Digest = Array[Byte]
opaque type KeyBits = Long

object BinaryKey:
  def apply(bytes: Array[Byte]): BinaryKey = bytes
  
  extension (key: BinaryKey)
    def bytes: Array[Byte] = key
    def hex: String = key.map("%02x".format(_)).mkString
```

### Domain Types

#### HashAlgo

Represents supported hashing algorithms:

```scala
enum HashAlgo:
  case SHA256
  case SHA512
  case BLAKE3
  case BLAKE2B
  
  def hash(bytes: Array[Byte]): Digest
  def bitLength: Int
```

#### BinaryKey

Content-addressable identifier:

```scala
final case class BinaryKey(
  algo: HashAlgo,
  digest: Digest,
  bits: KeyBits
)

object BinaryKey:
  given Schema[BinaryKey] = DeriveSchema.gen
  
  def fromHex(hex: String): Option[BinaryKey]
  def random: UIO[BinaryKey]
```

#### Interval & RangeSet

Represents byte ranges within blobs:

```scala
final case class Bound(value: Long)
final case class Interval(start: Bound, end: Bound)
final case class Span(offset: Long, length: Long)

final case class RangeSet(intervals: Chunk[Interval]):
  def union(other: RangeSet): RangeSet
  def intersect(other: RangeSet): RangeSet
  def complement: RangeSet
  def contains(point: Long): Boolean
```

## Schema Evolution

### Versioning Strategy

Graviton uses zio-schema's migration support:

```scala
object BlobManifestV1:
  final case class Manifest(
    key: BinaryKey,
    size: Long,
    entries: Chunk[BlockEntry]
  )
  
  given Schema[Manifest] = DeriveSchema.gen

object BlobManifestV2:
  final case class Manifest(
    key: BinaryKey,
    size: Long,
    entries: Chunk[BlockEntry],
    attributes: Map[String, String]  // New field
  )
  
  given Schema[Manifest] = DeriveSchema.gen
  
  // Migration path
  val migration: Migration[BlobManifestV1.Manifest, BlobManifestV2.Manifest] =
    Migration.apply { v1 =>
      BlobManifestV2.Manifest(
        key = v1.key,
        size = v1.size,
        entries = v1.entries,
        attributes = Map.empty  // Default for new field
      )
    }
```

### Backward Compatibility

All schema changes must:

1. **Add optional fields** — Never remove or change existing fields
2. **Provide defaults** — New fields must have sensible defaults
3. **Test migrations** — Validate round-trip encoding/decoding
4. **Document changes** — Update version history in this guide

## Codec Derivation

### Automatic Derivation

```scala
import zio.schema.*

final case class BlobMetadata(
  contentType: String,
  encoding: Option[String],
  checksum: Digest
)

object BlobMetadata:
  given Schema[BlobMetadata] = DeriveSchema.gen
  given JsonCodec[BlobMetadata] = JsonCodec.fromSchema
  given ProtobufCodec[BlobMetadata] = ProtobufCodec.fromSchema
```

### Custom Codecs

For performance-critical types:

```scala
object Digest:
  given Schema[Digest] = Schema[Array[Byte]]
    .transform(
      bytes => Digest(bytes),
      digest => digest.bytes
    )
  
  given BinaryCodec[Digest] = BinaryCodec.fromSchema
```

## Validation

### Refined Types

Using **iron** for compile-time validation:

```scala
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

opaque type PositiveSize = Long :| Positive
opaque type BoundedChunkSize = Long :| (Greater[256] & Less[4194304])

final case class ChunkConfig(
  minSize: PositiveSize,
  avgSize: PositiveSize,
  maxSize: BoundedChunkSize
)
```

### Runtime Validation

```scala
object BinaryKey:
  def validate(key: BinaryKey): IO[ValidationError, Unit] =
    for {
      _ <- ZIO.when(key.digest.length != key.algo.bitLength / 8) {
        ZIO.fail(ValidationError.InvalidDigestLength)
      }
      _ <- ZIO.when(key.bits < 0) {
        ZIO.fail(ValidationError.InvalidKeyBits)
      }
    } yield ()
```

## Attributes System

### BinaryAttributes

Metadata attached to blobs:

```scala
enum BinaryAttributeKey:
  case ContentType
  case Encoding
  case OriginalName
  case UploadTimestamp
  case CustomAttribute(name: String)

final case class BinaryAttributes(
  advertised: Map[BinaryAttributeKey, String],
  confirmed: Map[BinaryAttributeKey, String]
):
  def get(key: BinaryAttributeKey): Option[String] =
    confirmed.get(key).orElse(advertised.get(key))
  
  def validate: IO[ValidationError, BinaryAttributes]
```

### Confirmed vs Advertised

- **Advertised**: Client-provided metadata (untrusted)
- **Confirmed**: Server-validated attributes (e.g., actual size, hash)

```scala
for {
  advertised <- client.uploadBlob(bytes, attributes = Map(
    ContentType -> "application/pdf",
    OriginalName -> "document.pdf"
  ))
  
  // Server confirms actual attributes
  confirmed <- store.getAttributes(key).map { attrs =>
    attrs.copy(confirmed = Map(
      ContentType -> detectMimeType(bytes),
      ActualSize -> bytes.length.toString
    ))
  }
} yield confirmed
```

## Schema Registry

Future support for centralized schema management:

```scala
trait SchemaRegistry:
  def register[A: Schema](name: String, version: Int): UIO[SchemaId]
  def lookup(id: SchemaId): IO[SchemaNotFound, Schema[?]]
  def evolve[A, B](from: SchemaId, to: SchemaId): IO[EvolutionError, Migration[A, B]]
```

## Best Practices

### Type Safety

✅ **Do**: Use opaque types for domain primitives

```scala
opaque type TenantId = String
opaque type SessionId = UUID
```

❌ **Don't**: Use raw primitives

```scala
def upload(tenantId: String, sessionId: String, data: Array[Byte])
```

### Schema Organization

- **Separate versioned schemas** into dedicated objects
- **Group related types** in the same module
- **Document breaking changes** in migration objects

### Performance

- **Cache derived codecs** — Don't regenerate on every use
- **Use binary codecs** for internal communication
- **Profile serialization** — Measure before optimizing

## See Also

- **[Ranges & Boundaries](./ranges)** — Working with byte ranges
- **[Scans & Events](./scans)** — Event-driven scanning
- **[Runtime Ports](../runtime/ports)** — Service interfaces

::: tip
Use `DeriveSchema.gen` for most cases. Only write manual schemas for performance-critical hot paths.
:::
