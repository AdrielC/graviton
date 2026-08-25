# Manifests & Frames

Manifests describe how every blob is assembled inside Graviton. They list the ordered block keys, byte ranges, and derived attributes that the runtime needs to rehydrate a stream without re-reading the original upload. Frames wrap those manifests (and optional block payloads) in a versioned, authenticated binary envelope so different backends can share a single durability format.

## Manifest schema

`graviton.runtime.model.BlockManifest` is a thin container over a chunk of `BlockManifestEntry` values plus the aggregate uncompressed size. Each entry captures the canonical block hash, its ordinal position, and where it lands in the contiguous blob space:

| Field | Type | Description |
| --- | --- | --- |
| `index` | `BlockIndex` | Monotonic counter for the block’s position inside the blob. |
| `offset` | `Size` | Absolute byte offset where the block begins. |
| `key` | `BinaryKey.Block` | Content-addressed key derived from the block payload. |
| `size` | `BlockSize` | Refined size (max bounded by `MaxBlockBytes`). |

Manifests also carry the merged `BinaryAttributes` map so consumers can see which metadata was advertised by the client and which fields were confirmed by the runtime (`BinaryAttributes.confirm*`). The manifest’s `totalUncompressed` value is recomputed from the entries every time `BlockManifest.build` runs, preventing mismatched attribute sizes from ever reaching disk.

## Entry invariants and validation

`BlockManifestEntry.make` enforces the basic invariants:

- `index` and `offset` must be non-negative refined types.
- `size` is validated via `CanonicalBlock.refineBlockSize`, guaranteeing it never exceeds `MaxBlockBytes`.
- `BlockManifest.build` folds over the entries and ensures the total uncompressed byte count matches the sum of individual sizes.

Writers are expected to append entries in increasing offset order—`BlockManifest` does not reorder blocks for you. The ingest path maintains a running `Size` counter while chunking so the offsets line up with the deduplicated stream. Readers can therefore treat manifests as trustable truth: once a manifest loads, offsets and chunk counts already satisfy the runtime’s refined constraints.

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

When framing is enabled for a deployment, the intended flow is:

1. Chunk bytes through `BlockStore.putBlocks`, deriving canonical hashes and building `BlockManifestEntry` values.
2. Run `BlockFramer.synthesizeBlock` for each canonical block where the write plan is supported (plain block-per-frame today).
3. Persist frames and manifests according to the backend. Attributes are confirmed before the manifest is sealed so readers get the finalized metadata map.
4. During reads, load the manifest, verify headers/AAD where applicable, and stream blocks via the refs recorded in the manifest.

**Today**, many installs persist **manifest rows and block bytes** via `CasBlobStore` without exercising the full frame decode path above; treat heavy “decode every frame” language as **forward-looking** until read-side framing is documented per backend.

Because manifests, frames, and attributes use refined types from `graviton-core`, framer errors surface as `Either[String, _]` rather than thrown exceptions where the API returns `Either`.

## Related guides

- [Binary Streaming Guide](guide/binary-streaming.md) – how chunkers, block stores, and manifests interleave.
- [Ingest Chunking](ingest/chunking.md) – strategies for choosing block boundaries that still satisfy manifest invariants.
