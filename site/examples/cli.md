# CLI Example

The `graviton` CLI ingests and retrieves files via the local store.

```bash
# ingest a file and capture the returned FileKey
$ graviton put README.md
FILE_KEY=...

# retrieve the stored bytes
$ graviton get $FILE_KEY > README.copy.md
```
