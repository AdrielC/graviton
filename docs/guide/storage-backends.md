# Storage Backends (Current Server)

This page explains how the current `graviton-server` stores bytes for each backend choice, including the **exact on-disk / object-key layout**.

## Concepts: BlobStore vs BlockStore

- **`BlobStore`**: accepts/returns a full blob stream and yields a `BinaryKey.Blob`.
- **`BlockStore`**: stores and retrieves **canonical blocks** (`BinaryKey.Block`) that make up a blob.
- The current server wires a CAS blob store (`CasBlobStore`) over:
  - a **block backend** (filesystem or S3/MinIO), and
  - a **manifest repository** (Postgres).

This means:

- Blocks live in filesystem/S3.
- Manifests (block references) live in Postgres.

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

- Writes are **atomic** per block file (`ATOMIC_MOVE`).
- Duplicate blocks are detected by checking for file existence and treating a “file already exists” race as `Duplicate`.

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

## What is (and isn’t) persisted today

### Persisted

- **Block bytes** in the chosen block store
- **Manifest references** in Postgres
- **BlobId** returned from the HTTP API is derived from the blob hash + total byte length

### Not yet stable / evolving

- Deletion semantics (`CasBlobStore.delete` is not implemented)
- Blob “stat” API (`stat` returns `None` today)
- A stable HTTP error model (many failures surface as 500 with plain text)

## Related docs

- [Configuration Reference](./configuration-reference.md)
- [Run Locally (Full Stack)](./run-locally.md)
- [HTTP API](../api/http.md)
