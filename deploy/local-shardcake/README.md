# Local Shardcake cluster

This topology runs one Shardcake manager, two Graviton upload nodes, PostgreSQL,
and MinIO on a private Compose network. Both nodes share the manifest, catalog,
and block stores while Shardcake keeps each typed upload session local to one
live node.

All published host ports bind to `127.0.0.1`. The Graviton processes opt into a
container-network binding only so peer nodes and the manager can reach them;
the console still rejects cross-origin browser requests and is never wrapped in
the development API CORS policy.

```bash
./scripts/demo-shardcake-local.sh up
open http://127.0.0.1:58081/console
```

The second node is available at `http://127.0.0.1:58082/console`. Uploads use a
raw streaming request body, never multipart or base64. Folder entries are
mutable references to immutable CAS blobs, so removing a reference does not
silently delete shared content.

Use `status`, `logs`, or `stop` with the same script. `stop` preserves the named
PostgreSQL and MinIO volumes so deduplication and catalog state survive the next
start. Docker Swarm is not required for this proof because Shardcake owns
placement and reassignment; Compose supplies the distinct processes and network
identities needed to exercise those semantics locally.
