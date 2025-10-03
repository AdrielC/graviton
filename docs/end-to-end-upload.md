# End-to-end Upload

The upload path streams bytes from clients to durable storage while computing content hashes and building manifests.

1. **Session negotiation** – a client opens a gRPC bidi stream or HTTP session. The server allocates a shard-backed session entity with spill buffers and MultiHasher state.
2. **Chunking** – `graviton-streams` chunkers split the byte stream using either fixed-size or content-defined boundaries. Chunks feed incremental hashers from `graviton-core`.
3. **Storage layout** – the runtime derives `BinaryKey` values for blocks, chunks, and manifests. Locator strategies map these keys to backend-specific paths.
4. **Persistence** – the `MutableObjectStore` implementation uploads parts, respecting backend-specific minimum sizes (for example, 5 MiB for S3 multipart uploads). Range trackers record which spans succeeded.
5. **Manifest emission** – once all blocks are written, a manifest describing order, sizes, and attributes is persisted. The blob key is returned to the client alongside metadata captured in `BlobWriteResult`.
6. **Replication** – optional background jobs use `ReplicaIndex` and `UnionFind` utilities to drive additional copies or repairs.
