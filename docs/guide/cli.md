# CLI and Server Usage

## One-command local proof

```bash
./scripts/demo-local.sh
```

The script ingests a file, starts fresh CLI JVMs for metadata, retrieval, and verification, and compares the result byte-for-byte. It prints the generated data directory for inspection.

## CLI

The `graviton-cli` module uses `FsBlockStore` and `FsBlobManifestRepo` under `GRAVITON_DATA_DIR`. Both blocks and manifests survive process restarts.

```bash
export GRAVITON_DATA_DIR=/tmp/graviton-data

./sbt "cli/run ingest /path/to/file"
```

Ingest prints a stable content ID:

```text
Blob ID: sha-256:<hex-digest>:<byte-length>
```

Use that complete ID for later commands:

```bash
./sbt "cli/run stat $BLOB_ID"
./sbt "cli/run get $BLOB_ID /tmp/retrieved.bin"
./sbt "cli/run verify $BLOB_ID"
./sbt "cli/run delete $BLOB_ID"
```

`get` streams to a temporary file and atomically replaces the requested output only after retrieval succeeds. `verify` hashes the stream incrementally. Neither command buffers the complete blob.

`delete` removes only the manifest. Content-addressed blocks remain because other manifests may share them.

### Filesystem layout

```text
$GRAVITON_DATA_DIR/
└── cas/
    ├── blocks/<algorithm>/<digest>-<size>
    └── manifests/<algorithm>/<digest>-<size>.manifest
```

Manifest files use the bounded, versioned `FramedManifest` codec and atomic replacement.

## HTTP server

The deployable server defaults to the same durable filesystem composition as the CLI. It starts without PostgreSQL, MinIO, or other external services.

```bash
./sbt "server/run"
```

Set `GRAVITON_BLOB_BACKEND=s3` or `minio` to use S3-compatible blocks with PostgreSQL manifests. See [Run Locally](./run-locally.md) for both paths.

### Curl round-trip

```bash
BLOB_ID="$(
  curl -fsS \
    -H "Content-Type: application/octet-stream" \
    -X POST --data-binary @/path/to/file \
    "http://localhost:8081/api/blobs" \
  | jq -r .
)"

curl -fsSI "http://localhost:8081/api/blobs/$BLOB_ID"
curl -fsS "http://localhost:8081/api/blobs/$BLOB_ID" --output /tmp/retrieved.bin
```

The API also supports `DELETE /api/blobs/:id`. See [HTTP API](../api/http.md) for status codes and response headers.

## Failure behavior

- Invalid content IDs return HTTP 400 and a structured JSON error.
- Unknown valid IDs return HTTP 404 before a streaming response begins.
- The CLI exits with failure for missing blobs and failed verification.
- Corrupt filesystem manifests fail closed during decode.

## See also

- [Configuration Reference](./configuration-reference.md)
- [Storage Backends](./storage-backends.md)
- [HTTP API](../api/http.md)
