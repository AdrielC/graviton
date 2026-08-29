# Performance Measurement

Graviton includes real HTTP measurement and soak tools. The repository does not publish a universal throughput or latency claim because backend, hardware, network, payload distribution, concurrency, and duplication materially change results.

## One verified measurement

Start a server, then run:

```bash
GRAVITON_BENCHMARK_BACKEND_DESCRIPTION='filesystem, APFS, local process, 1 MiB chunks' \
  ./scripts/benchmark-http.sh \
  http://localhost:8081 \
  ./representative-payload.bin \
  > benchmark.json
```

For a secured server, export `GRAVITON_BEARER_TOKEN`. The token is used as an HTTP header and is not written to the result.

The script performs a real upload, byte-for-byte download comparison, and server-side verification. Its JSON includes:

- schema version and UTC timestamp
- exact Git revision and dirty-worktree state
- operating system, Python, and Java version
- operator-supplied backend description
- payload byte count and SHA-256
- fresh and duplicate block counts reported by the server
- upload and download duration and MiB/s
- server-side ingest duration
- verification duration
- returned blob content ID

This is one measured sample, not a distribution.

## Soak loop

```bash
./scripts/soak-http.sh http://localhost:8081 1000 1048576 \
  > soak.json
```

Every iteration uploads real bytes and compares the downloaded stream. The command fails if any iteration fails and emits machine-readable iteration, payload, duration, and failure counts. It intentionally uses a repeated payload to exercise duplicate-block behavior. Use a representative corpus for broader qualification.

## Required benchmark record

Before publishing a result, retain:

- raw JSON and command line
- exact commit and clean/dirty state
- server and client host topology
- CPU, memory, operating system, JDK, filesystem, object store, and database versions
- Graviton backend, chunk size, security mode, and non-default limits
- dataset source, checksum, size distribution, and duplication ratio
- warm-up, iterations, concurrency, ordering, and cache state
- p50, p95, and p99 from retained raw samples
- error and retry counts

Never infer throughput from block size or a single process counter. Never compare two revisions using different data, hosts, caches, or backend settings without labeling the difference.

## CAS ingest concurrency and memory

The server defaults to four concurrent block writes per upload. Set `GRAVITON_BLOCK_WRITE_PARALLELISM` between `1` and `64` to match the backend and its connection pool. Results remain emitted in source order, so the manifest is deterministic even when writes complete out of order.

The live-byte ceiling controlled by Graviton is:

```text
input queue chunks * I/O chunk bytes
+ block queue entries * chunker maximum block bytes
+ (block write parallelism + 1) * chunker maximum block bytes
```

This excludes one caller-owned input chunk, the chunker's documented working set, and backend-local buffers. With the defaults and a 1 MiB fixed chunker, the Graviton-owned ceiling is 7,602,176 bytes per active ingest.

Parallel block writes mainly target object stores. The S3 adapter creates a new block with one conditional `PutObject`; it does not issue a speculative `HeadObject`. If the key already exists, the rejected conditional write is followed by a metadata-only `HeadObject` that proves the stored length, content key, and SHA-256 checksum. Objects written before proof metadata was introduced use one bounded `GetObject` for exact-byte verification. Do not assume that increasing parallelism improves a local filesystem. Measure the selected backend and keep concurrency bounded.

The S3 adapter materializes each already-bounded CAS block once because the AWS synchronous request body requires a replayable body for checksums and retries. This adds at most one block-size byte array per active block write. With the default 1 MiB chunker and four concurrent writes, that backend-local allowance is 4 MiB. At the supported 16 MiB maximum block size and 64-way maximum parallelism, the theoretical configuration ceiling is 1 GiB per upload, so operators should not combine both maxima without an explicit memory budget.

The opt-in MinIO integration gate uploads the same 32 MiB stream twice with 1 MiB blocks, verifies the reconstructed content by streaming it through the hasher, asserts 32 fresh blocks followed by 32 duplicate blocks, and records both elapsed times in the CI log. Those figures are a regression signal for that runner, not a portable throughput claim.

## Inline CAS versus staged acceptance

The implemented upload endpoint performs CAS inline: it reads the stream once, sniffs a bounded prefix, selects a chunker, hashes the complete blob, hashes each bounded block, persists blocks with back pressure, and atomically publishes the manifest last. The response therefore contains the final content ID.

An asynchronous staging mode can reduce request completion time only by changing the contract to `202 Accepted`. It does not remove CAS work. A worker must still read every staged byte to validate length and media type, choose the chunker, derive block and blob hashes, persist unique blocks, and commit the manifest. Compared with inline CAS, staging adds one full temporary-object write and one full temporary-object read.

Staging is useful for resumable multipart upload, quarantine, admission control, or absorbing backend outages. It should use a typed upload receipt rather than pretending the temporary locator is a content ID, plus a durable state machine, idempotent worker lease, expiry, and orphan cleanup. The staged object must stream through the same ingest service and must never be materialized in memory. This mode is not implemented by the current HTTP API.

S3 multipart upload belongs at that whole-object staging boundary. Graviton CAS blocks are bounded to at most 16 MiB and default to 1 MiB, so bounded concurrent single-object block writes are the appropriate default rather than multipart upload per block.

## Comparing revisions

1. Use two clean clones with the same JDK.
2. Use immutable payloads and record checksums.
3. Use fresh storage roots or equivalent backend namespaces.
4. Warm both revisions with the same unmeasured workload.
5. Randomize run order.
6. Capture individual samples rather than only an average.
7. Test cold and warm reads separately.
8. Treat changes smaller than run-to-run variation as inconclusive.

## Current observability boundary

`/api/stats` and `/metrics` expose process-local ingest, deduplication, HTTP request, error, and latency observations. They reset on restart and do not establish durable capacity or a service-level objective. Backend-specific histograms and retained dashboards remain deployment work.
