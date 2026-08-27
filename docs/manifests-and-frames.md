# Manifests & Frames

Manifests describe how every blob is assembled inside Graviton. They list ordered block keys and byte ranges so the runtime can rehydrate a stream without re-reading the original upload. Frames are a separate bounded model for block transport and future transforms; the operational filesystem CAS uses `GVM2` streaming manifests and PostgreSQL uses relational manifest rows.

## Manifest schema

During ingest, `BlockManifestEntry` captures the committed block hash, its ordinal position, and where it lands in the contiguous blob space:

| Field | Type | Description |
| --- | --- | --- |
| `index` | `BlockIndex` | Monotonic counter for the block’s position inside the blob. |
| `offset` | `Size` | Absolute byte offset where the block begins. |
| `key` | `BinaryKey.Block` | Content-addressed key derived from the block payload. |
| `size` | `BlockSize` | Refined size (max bounded by `MaxBlockBytes`). |

The runtime keeps these entries in a scoped disk spool until the full blob key is known. It then persists semantic `ManifestEntry` records. Confirmed `BinaryAttributes` are returned by the write operation but are not embedded in the current filesystem or PostgreSQL CAS manifest.

## Entry invariants and validation

`BlockManifestEntry.make` enforces the basic invariants:

- `index` and `offset` must be non-negative refined types.
- `size` is validated via `CanonicalBlock.refineBlockSize`, guaranteeing it never exceeds `MaxBlockBytes`.
- The streaming spool and durable writers enforce consecutive indices, contiguous offsets, exact block-key sizes, the declared entry count, and the declared total size.

Writers append entries in increasing offset order and never reorder blocks. Filesystem readers repeat the structural validation while streaming `GVM2`; PostgreSQL writes validate each 512-entry batch inside a transaction. Materialized inspection is capped at 16,384 entries, while reconstruction streams up to the 1,048,576-entry logical ceiling.

## Operational persistence formats

- Filesystem: `GVM2` magic, total-size and block-count header, then length-delimited key, offset, and length records. Publication uses a forced temporary file and atomic rename. Legacy `FramedManifest` version 1 remains readable.
- PostgreSQL: one `graviton.blob` summary and ordered `graviton.blob_block` rows. Writes are transactional and batched; reads use a forward cursor with auto-commit disabled so JDBC fetch size is effective.
- In-memory: a bounded compatibility implementation intended for tests and short-lived applications.

## Framing pipeline

`BlockFramer.synthesizeBlock` exposes the implemented format only: one canonical block per plain frame. Compression, encryption, and aggregate layouts are not constructible `BlockWritePlan` options. This keeps a public plan from accepting a configuration that cannot execute.

While manifests are pure data, storage backends can wrap them in binary frames generated from `BlockWritePlan` and `FrameSynthesis`:

1. Ingest chooses a `BlockWritePlan` and whether duplicate blocks should be forwarded downstream.
2. `BlockFramer.synthesizeBlock` derives a `FrameHeader`, builds structured associated context, and emits a plain `BlockFrame`.
3. The resulting frame carries the header, encoded context, and bounded canonical block payload.

### Frame header layout

`FrameHeader` is shared across block, manifest, attribute, and index frames:

- `version`: current format version (defaults to `1` via `BlockFramer.FrameVersion`).
- `frameType`: one of `Block`, `Manifest`, `Attribute`, or `Index`.
- `algorithm`: `Plain` for frames synthesized by this release. Other enum values are reserved for decoding/version evolution and are not write-plan options.
- `payloadLength`: length of the bytes that follow the header.
- `aadLength`: length of the serialized AAD blob.
- `keyId`/`nonce`: optional encryption metadata for AEAD modes.

Because the header is schema-driven (`zio.schema`), expanding the enum or adding optional fields does not break binary compatibility—old readers can skip unknown tags.

### Additional authenticated data (AAD)

Frames capture structured context without leaking it into the payload:

- `FrameContext` provides per-upload inputs such as `orgId`, `blobKey`, `policyTag`, and the running block index.
- `FrameAadPlan` controls which fields are included and allows bounded block context to carry additional key/value pairs.
- `BlockFramer` materializes this plan into a `FrameAad` and encodes it with the frame codec.

The context records which blob and organization a block belonged to without changing its content-addressed identity.

### Algorithms and layouts

`FrameSynthesis` currently has a single executable layout and transform combination: `BlockPerFrame`, `CompressionPlan.Disabled`, and `EncryptionPlan.Disabled`. Future transforms require both write and read implementations, key-provider boundaries where applicable, and retained compatibility vectors before they become public plan variants.

## Forward-compatibility (design goals)

The manifest + frame design aims for several durability properties as the format matures:

- **Versioned header** – every frame begins with the `FrameVersion` byte. Future releases can bump this and still parse older frames because the version guards the decoder path.
- **Extensible enums** – `FrameType`, `FrameAlgorithm`, and related enums are schema-based; adding new cases does not change the binary layout of existing ones.
- **Length-prefixed sections** – both payload and AAD lengths live in the header, allowing readers to skip unfamiliar sections safely.
- **Optional metadata** – `FrameAad.extra` and manifest attributes can introduce new keys without invalidating older clients. Unknown keys are ignored while still being authenticated.
- **Strict size accounting** – `BlockManifest.build` refuses to produce manifests where totals drift, so deduped replay remains safe even if new attributes appear later.

Together these rules define the current plain-frame compatibility contract. New algorithms are a future versioned format change, not a silently unsupported runtime branch.

## Validation and decoding flow

The implemented frame flow is:

1. Chunk bytes through `BlockStore.putBlocks`, deriving canonical hashes and building `BlockManifestEntry` values.
2. Run `BlockFramer.synthesizeBlock` for each canonical block where the write plan is supported (plain block-per-frame today).
3. Persist the frame through a caller-selected frame path. The main CAS block stores persist canonical block bytes directly.
4. CAS reads stream manifest refs, fetch each bounded block, and verify its declared length and digest before emission.

`CasBlobStore` does not silently enable compression or encryption. Those plans remain unavailable until matching write, read, key-management, and compatibility implementations exist.

Because manifests, frames, and attributes use refined types from `graviton-core`, framer errors surface as `Either[String, _]` rather than thrown exceptions where the API returns `Either`.

## Related guides

- [Binary Streaming Guide](guide/binary-streaming.md) – how chunkers, block stores, and manifests interleave.
- [Ingest Chunking](ingest/chunking.md) – strategies for choosing block boundaries that still satisfy manifest invariants.
