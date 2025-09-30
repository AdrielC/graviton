# CLI Example

The `graviton` CLI ingests and retrieves blobs via the local store.

```bash
# ingest bytes and capture the returned BlobKey
$ graviton put README.md
BLOB_KEY=...

# retrieve the stored bytes
$ graviton get $BLOB_KEY > README.copy.md
```
