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

CAS writes use the single `GVM2` format: a fixed header followed by length-delimited block-key, offset, and length records. Entries are written and read incrementally, with strict count, contiguity, key-size, trailing-byte, and total-size checks. Writes use a temporary file, force contents, and atomically replace the destination. Files without the current `GVM2` header are rejected. Large manifests remain available to `stat` and `BlobStore.get`; the explicitly materialized `inspect` operation rejects them above 16,384 entries.

## S3-compatible blocks (`GRAVITON_BLOB_BACKEND=s3|minio`)

### What to set

```bash
export GRAVITON_BLOB_BACKEND="minio" # or "s3"
export GRAVITON_S3_ENDPOINT="http://localhost:9000"
export GRAVITON_S3_ACCESS_KEY="minioadmin"
export GRAVITON_S3_SECRET_KEY="minioadmin"

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
- New blocks use `PutObject` with `If-None-Match: *`, an explicit SHA-256 checksum, and Graviton proof metadata. A fresh write therefore needs no preliminary `HeadObject` and cannot overwrite a racing writer.
- Duplicate writes verify the stored length, content key, and SHA-256 checksum with `HeadObject`, without downloading block bytes. Objects without the complete current proof metadata are rejected.
- The object store must preserve user metadata and support conditional `PutObject` requests. A backend that cannot enforce `If-None-Match: *` is not compatible with the CAS write contract.
- Credentials are static (access key id + secret access key).

### Ceph Object Gateway

[Ceph Object Gateway](https://docs.ceph.com/en/latest/radosgw/) exposes an S3-compatible API, so Graviton can address RGW through the existing `graviton-s3` adapter:

```bash
export GRAVITON_BLOB_BACKEND="s3"
export GRAVITON_S3_ENDPOINT="https://rgw.example.internal"
export GRAVITON_S3_ACCESS_KEY="..."
export GRAVITON_S3_SECRET_KEY="..."
export GRAVITON_S3_BLOCK_BUCKET="graviton-blocks"
```

This is not a native RADOS backend. Graviton has no `librados` client or RADOS `BlockStore`, and CI currently qualifies the S3-compatible path against MinIO rather than a Ceph cluster. The `ceph` locator accepted by the repository's legacy combined DDL does not create a working backend by itself.

Graviton already chunks and content-addresses bytes before writing block objects. Equal blocks map to the same object key, so a Graviton bucket does not create separate RGW objects for identical content. Ceph's current [full RGW object deduplication](https://docs.ceph.com/en/latest/radosgw/s3_objects_dedup/) targets identical data behind distinct RGW objects; its own documentation labels execution experimental and warns that it can cause data loss. Do not run it on a production Graviton bucket. Track Graviton's byte-weighted logical reuse separately from Ceph's physical pool utilization.

## What is persisted today

### Persisted

- **Block bytes** in the chosen block store
- **Manifest references** in Postgres for the S3/MinIO server paths
- **Streaming `GVM2` manifest files** for `Graviton.fs`, `graviton-cli`, and default server mode
- **BlobId** returned from the HTTP API is derived from the blob hash + total byte length

### Deletion and metadata semantics

- `stat` returns manifest-derived size, digest, and ingestion timestamp.
- `delete` removes the blob manifest and retains shared content-addressed blocks.
- The pre-1.0 HTTP surface returns structured errors for invalid IDs, missing blobs, and storage failures.

## Related docs

- [Configuration Reference](./configuration-reference.md)
- [Run Locally (Full Stack)](./run-locally.md)
- [HTTP API](../api/http.md)
