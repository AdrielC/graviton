# Storage Backends (Current Server)

This page explains how the current `graviton-server` stores bytes for each backend choice, including the **exact on-disk / object-key layout**.

## Concepts: BlobStore vs BlockStore

- **`BlobStore`**: accepts/returns a full blob stream and yields a `BinaryKey.Blob`.
- **`BlockStore`**: stores and retrieves **canonical blocks** (`BinaryKey.Block`) that make up a blob.
- The current server wires a CAS blob store (`CasBlobStore`) over a block backend and a manifest repository.

This means:

- Default `fs` mode stores blocks and streaming manifests under the same filesystem root.
- `s3` and `minio` modes store blocks in S3-compatible object storage and manifests in PostgreSQL.

Embedded applications and the CLI use the same `Graviton.fs(root)` composition as default server mode.

## Filesystem blocks (`GRAVITON_BLOB_BACKEND=fs`)

### What to set

```bash
export GRAVITON_BLOB_BACKEND="fs"
export GRAVITON_FS_ROOT="./.graviton"
export GRAVITON_FS_BLOCK_PREFIX="cas/blocks"
```

### Layout (exact)

`FsBlockStore` stores blocks under:

- `<root>/<prefix>/<algo>/<hex>-<size>`

Example:

- `./.graviton/cas/blocks/blake3/0123abcd...-1048576`

### Operational notes

- Writes use a temporary file plus an atomic move where the filesystem supports it.
- Existing content-key paths are accepted as duplicates only after their bytes match the incoming block.
- Reads and existence checks reject a symbolic link or other non-regular entry at the final block path.

## Filesystem manifests (`Graviton.fs`, CLI, and default server)

`FsBlobManifestRepo` stores manifests under:

- `<root>/cas/manifests/<algo>/<hex>-<size>.manifest`

New CAS writes use the versioned `GVM2` format: a fixed header followed by length-delimited block-key, offset, and length records. Entries are written and read incrementally, with strict count, contiguity, key-size, trailing-byte, and total-size checks. Writes use a temporary file, force contents, and atomically replace the destination.

Legacy version-1 `FramedManifest` files remain readable. Their materialized decoder retains its 16,384-entry and 64 MiB safety limits. Large `GVM2` manifests remain available to `stat` and `BlobStore.get`; the explicitly materialized `inspect` operation rejects them above 16,384 entries.

## S3 / MinIO blocks (`GRAVITON_BLOB_BACKEND=s3|minio`)

### What to set (MinIO-style contract)

```bash
export GRAVITON_BLOB_BACKEND="minio" # or "s3"
export QUASAR_MINIO_URL="http://localhost:9000"
export MINIO_ROOT_USER="minioadmin"
export MINIO_ROOT_PASSWORD="minioadmin"

export GRAVITON_S3_BLOCK_BUCKET="graviton-blocks"  # optional (default shown)
export GRAVITON_S3_BLOCK_PREFIX="cas/blocks"       # optional (default shown)
export GRAVITON_S3_REGION="us-east-1"              # optional (default shown)
```

### Layout (exact)

`S3BlockStore` stores blocks under:

- `<prefix>/<algo>/<hex>-<size>`

Example:

- `cas/blocks/blake3/0123abcd...-1048576`

### Operational notes

- Existence checks use `HeadObject`; missing keys return `false` (MinIO sometimes uses a generic `S3Exception` for missing keys).
- Puts use `PutObject` with `contentLength` set from the block bytes.
- Credentials are static (access key id + secret access key).

## What is persisted today

### Persisted

- **Block bytes** in the chosen block store
- **Manifest references** in Postgres for the S3/MinIO server paths
- **Streaming `GVM2` manifest files**, with legacy framed-manifest reads, for `Graviton.fs`, `graviton-cli`, and default server mode
- **BlobId** returned from the HTTP API is derived from the blob hash + total byte length

### Deletion and metadata semantics

- `stat` returns manifest-derived size, digest, and ingestion timestamp.
- `delete` removes the blob manifest and retains shared content-addressed blocks.
- The pre-1.0 HTTP surface returns structured errors for invalid IDs, missing blobs, and storage failures.

## Related docs

- [Configuration Reference](./configuration-reference.md)
- [Run Locally (Full Stack)](./run-locally.md)
- [HTTP API](../api/http.md)
