# Portable Byte Encodings

`graviton-bytes` defines the small, cryptography-neutral `Hashable[A]` typeclass used to expose a value as one or more immutable `Chunk[Byte]` values. The same source is published for JVM, Scala.js, and Scala Native.

This is intentionally not ZIO Prelude `Hash[A]`. Prelude's typeclass produces a 32-bit hash code for equality and collection operations. Graviton's `Hashable[A]` defines the canonical bytes consumed by a cryptographic engine.

Built-in instances cover `Chunk[Byte]`, `ByteVector`, UTF-8 `String`, and `Byte`. Mutable `Array[Byte]` has no instance. Applications can define framed encodings without concatenating a complete value:

```scala
import graviton.bytes.Hashable
import zio.Chunk

final case class Envelope(header: Chunk[Byte], body: Chunk[Byte])

given Hashable[Envelope] =
  Hashable.instance(value => Chunk(value.header, value.body))
```

The shared contract verifies UTF-8 and explicit framing with the same vectors on all three runtimes. Platform hash engines may use different provider APIs, but must consume these exact chunks and pass algorithm golden vectors before they can claim compatible content identities.

The JVM `graviton-core` artifact re-exports this typeclass from `graviton.core.bytes.Hashable` for its hashing and streaming APIs. The storage engine remains JVM-only.

## Mutable interop boundaries

Public hashing APIs accept immutable values and return immutable `Chunk[Byte]`-backed digest types. `Digest` does not expose an `Array[Byte]`, and mutable arrays are not `Hashable`.

Some JVM libraries require arrays. Graviton confines those copies to the immediate boundary:

- JCA message digests receive one immutable input segment at a time.
- JDBC receives a fresh digest copy bounded to 16–32 bytes.
- Protobuf receives one already-bounded transport chunk at a time.
- Filesystem and AWS SDK writes receive one `Block` or configured multipart part, each bounded before conversion.

The package-private `Digest.fromArrayCopy` and `Digest.toInteropArray` adapters make digest crossings explicit. They defensively copy, enforce the supported digest interval, and must not be used as application-level byte containers. Whole blobs remain `ZStream` values and are never materialized for interop.
