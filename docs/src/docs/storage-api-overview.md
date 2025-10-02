# Graviton Storage API & Frame Design Overview

This document expands on the core `BinaryStore` design, its key abstractions,
and the frame format that underpins deduplicated storage. It captures the
current architecture and the near-term roadmap for extending ingestion,
metadata, and decoding capabilities.

## Objectives

- Provide a unified, type-safe API for storing and retrieving binary data.
- Support both block-deduplicated and streaming ingestion modes.
- Track rich metadata from multiple sources (client, server, build info,
  scanners, and more).
- Use ZIO 2.x and Iron refined types to eliminate illegal states at compile
  time.
- Prepare the APIs for future extensions such as multi-part uploads, content
  classifiers, and Merkle-tree manifests.

## Binary Key Design

Every stored object is addressed by a `BinaryKey`. The type is opaque outside
its module and exposes only two public shapes:

- **`BinaryKey.CasKey`** &mdash; a content-addressable key produced by hashing the
  entire blob. Identical inputs always yield the same key, and the hashing
  strategy remains an internal detail.
- **`BinaryKey.WritableKey`** &mdash; a caller-supplied key. It comes in three
  flavours:
  - `random` keys generated via `zio.Random.nextUUID` and encoded into a
    collision-safe representation.
  - `static` keys validated eagerly (via Scala 3 macros at compile time or at
    runtime when supplied by external callers).
  - `scoped` keys composed from a non-empty scope and name, encoded as
    `scope:key` to prevent accidental collisions.

A future `BinaryKeyMatcher` will provide a pure predicate AST that backends can
compile into efficient prefix or grouping queries when listing keys.

## BinaryStore API Surface

`BinaryStore` is the primary entry point for storing and retrieving binary
content. Its streaming-friendly operations focus on incremental uploads and
content-addressable semantics:

- `insert`: `ZSink[Any, Throwable, Byte, Byte, BinaryKey.CasKey]`. Consumes a
  stream of bytes, computes the content-addressable key, and returns it. The
  sink uses `peel` to read the minimum number of bytes required for hashing and
  metadata detection, leaving any unread data in the leftover stream.
- `insertWith(key: BinaryKey.WritableKey)`: `ZSink[Any, Throwable, Byte, Byte,
  Boolean]`. Writes bytes under a caller-provided key and returns `true` when
  the key is new, or `false` if an existing value was overwritten.
- `insertWith(keys: TaskStream[BinaryKey.WritableKey])`: a multi-key variant that
  accepts a stream of writable keys. Each emitted chunk is written under the
  next key, producing `true` when the slot was empty and `false` when it
  replaced a value.
- `exists(key: BinaryKey)`: `IO[Throwable, Boolean]` to test whether a key is
  present.
- `findBinary(key: BinaryKey)`: `IO[Option[ZStream[Any, Throwable, Byte]]]` to
  fetch and stream the stored bytes, with transparent decryption and
  decompression.
- `listKeys(matcher: BinaryKeyMatcher)`: `ZStream[Any, Throwable, BinaryKey]` to
  list keys that satisfy the provided predicate.
- `copy(from, to: BinaryKey.WritableKey)`: `IO[Throwable, Unit]` to duplicate a
  blob without reuploading data.
- `delete(key: BinaryKey)`: `IO[Throwable, Boolean]` to remove a blob and report
  whether any data was deleted.

A planned `insertChunks` sink will add multi-part uploads that return manifests
of `BinaryKey`s, enabling resumable uploads and future Merkle-tree structures.

## Binary Attributes

Metadata is modelled with `BinaryAttributeKey[A]` and stored in
`BinaryAttributes`:

- A `BinaryAttributeKey[A]` defines a strongly-typed attribute with an attached
  `Schema[A]`.
- `BinaryAttributes` keeps two `ListMap`s, one for **advertised** values supplied
  by clients and one for **confirmed** values observed by servers. Each map
  stores entries as `DynamicValue`s so that callers can rehydrate them into
  typed data when needed.
- Callers may advertise claims such as file size or MIME type; the server
  verifies these claims and records the actual values alongside the supplied
  metadata.
- Common built-in keys include `size`, `contentType`, `createdAt`, and
  `ownerOrgId`, while projects remain free to introduce custom keys.

## Streaming Ingest Workflow

During `insert` or `insertWith`, Graviton relies on `peel` to:

1. Read the smallest prefix required to compute the content hash, detect MIME
   types, or gather other metadata.
2. Detect whether the payload exceeds the configured single-upload threshold and
   surface leftover bytes so callers can retry or initiate a multi-part upload.
3. Avoid loading the entire file into memory. Future refinements may employ
   `foldWeightedDecompose` to automatically segment multi-part uploads.

## Security and Key Management

- **Encryption**: Data is encrypted by default using a per-organisation or
  per-document KMS key. The encryption strategy is hidden behind the
  `BinaryStore` API.
- **Authentication**: Graviton integrates with OIDC/JWT providers such as
  Keycloak to authenticate users and enforce authorisation policies.
- **Pre-signed URLs**: Optional pre-signed URLs support public or delegated
  access patterns while remaining outside the core storage API.

## Frame Format and Decoding

Each stored block is wrapped in a self-describing frame that enables block-level
compression, encryption, and deduplication. The frame contains:

1. Magic bytes and a version identifier.
2. Flags and algorithm identifiers (hash, compression, encryption).
3. Plaintext, compressed, and ciphertext size fields.
4. Nonce and integrity hash values.
5. Optional metadata such as dictionary IDs, file IDs, or chunk indices.
6. The payload: encrypted bytes that may also be compressed.

### Header-First Decoding Strategy

A header-first approach lets readers choose the right decoding pipeline before
streaming the payload:

1. **Length-prefix the header** with a varint or 32-bit integer so readers know
   the header length.
2. **Decode the header** into a `FrameHeader` via scodec or ZIO Schema Protobuf.
3. **Build the pipeline** based on the header’s algorithm identifiers:
   - Decrypt the next `sizeCipher` bytes using the nonce and key ID when
     encryption is enabled.
   - Decompress the decrypted data when compression flags are set, optionally
     reusing a dictionary ID.
4. **Stream the payload** by reading exactly `sizeCipher` bytes and passing them
   through the pipeline to reproduce the original plaintext bytes.

### scodec Option

- Define a `Codec[FrameHeader]` with scodec to describe the bit-level layout.
- Implement a `ZStreamDecoder` that reads the minimal number of bits required to
  decode a header, returns the unused remainder, and then streams the payload.

### ZIO Schema Protobuf Option

- Define a `FrameHeader` case class with `@fieldNumber` annotations and derive a
  `BinaryCodec[FrameHeader]` via `zio-schema-protobuf`.
- Prefix the header with its length, read that many bytes, and call
  `FrameHeader.codec.decode` to obtain a typed header for dynamic pipeline
  selection.
- With explicit field numbers, the Protobuf schema can evolve while remaining
  backward compatible.

## Future Work

Upcoming enhancements build on this foundation:

- `BinaryKeyMatcher` for prefix and grouped listing queries across backends.
- Multi-part ingestion via `insertChunks`, returning manifests of `BinaryKey`s.
- Streaming content classifiers (virus scanners, PII detectors, etc.) that run
  during ingest.
- Versioned writable keys that retain multiple revisions under a logical name.
- Merkle-tree manifests that enable efficient diffs and partial reads.

These plans emphasise type safety, streaming efficiency, metadata correctness,
and extensibility, ensuring Graviton remains a robust content-addressable
storage platform.
