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
For a Shardcake-enabled server, set both `GRAVITON_BENCHMARK_TENANT_ID`
and `GRAVITON_BENCHMARK_SESSION_ID` to canonical UUIDs. Set
`GRAVITON_BENCHMARK_MEDIA_TYPE` when the declared media type matters to
chunker selection. These control values are sent as headers and are not
written to the result.

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

## Reproducible distribution

Use the suite wrapper for retained raw samples and a p50, p95, and p99 summary:

```bash
GRAVITON_BENCHMARK_BACKEND_DESCRIPTION='MinIO + PostgreSQL, two Shardcake nodes' \
GRAVITON_BENCHMARK_WARMUPS=3 \
  ./scripts/benchmark-suite.sh \
  http://127.0.0.1:58081 \
  ./representative-payload.bin \
  30 \
  4 \
  benchmark-results
```

The wrapper never holds payload bytes in memory. Each worker delegates to the
streaming HTTP benchmark and retains one bounded JSON control record. The
output directory contains every raw sample, a newline-delimited copy, and a
summary with latency and throughput distributions. A failed or unverified
sample fails the suite instead of disappearing from the aggregate.

## ZIO HTTP transport choices

Graviton currently resolves ZIO HTTP 3.11.4 and explicitly enables request streaming. Upload handlers consume `request.body.asStream`; download handlers use `Body.fromStream(stream, contentLength)` because manifest size is known. This keeps Netty demand connected to the ZIO stream and avoids whole-body aggregation.

The server retains ZIO HTTP's 64 KiB pre-connect request-body buffer, TCP no-delay, and keep-alive defaults. It does not enable `avoidContextSwitching`: upload handlers hash, chunk, and persist before their first asynchronous boundary, which is the workload the option warns can monopolize an event loop. Global response compression and request decompression also remain disabled because CAS payloads are commonly already compressed and decompression would change the bytes being content-addressed.

See the official [server](https://ziohttp.com/reference/server/), [body](https://ziohttp.com/reference/body/), and [client](https://ziohttp.com/reference/client/) references and the [3.11.4 release](https://github.com/zio/zio-http/releases/tag/v3.11.4).

## Soak loop

```bash
./scripts/soak-http.sh http://localhost:8081 1000 1048576 \
  > soak.json
```

Every iteration uploads real bytes and compares the downloaded stream. The command fails if any iteration fails and emits machine-readable iteration, payload, duration, and failure counts. It intentionally uses a repeated payload to exercise duplicate-block behavior. Use a representative corpus for broader qualification.

## Local failure qualification

With the two-node local topology already running, execute:

```bash
./scripts/qualify-local-shardcake.sh > failure-proof.json
```

The harness uses disposable payload files and does not delete Compose volumes.
It proves cross-node reuse, byte-exact readback, an interrupted request that
does not publish a manifest, node drain and reassignment, object-store outage
readiness, manifest-store outage readiness, recovery, and final two-node
readiness. A trap restarts any service the harness stopped if the run fails.

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

Parallel block writes mainly target object stores. The S3 adapter creates a new block with one conditional `PutObject`; it does not issue a speculative `HeadObject`. If the key already exists, the rejected conditional write is followed by a metadata-only `HeadObject` that proves the stored length, content key, and SHA-256 checksum. Missing or inconsistent proof metadata fails closed without downloading the object. Do not assume that increasing parallelism improves a local filesystem. Measure the selected backend and keep concurrency bounded.

The S3 adapter materializes each already-bounded CAS block once because the AWS synchronous request body requires a replayable body for checksums and retries. This adds at most one block-size byte array per active block write. With the default 1 MiB chunker and four concurrent writes, that backend-local allowance is 4 MiB. At the supported 16 MiB maximum block size and 64-way maximum parallelism, the theoretical configuration ceiling is 1 GiB per upload, so operators should not combine both maxima without an explicit memory budget.

The opt-in MinIO integration gate uploads the same 32 MiB stream twice with 1 MiB blocks, verifies the reconstructed content by streaming it through the hasher, asserts 32 fresh blocks followed by 32 duplicate blocks, and records both elapsed times in the CI log. Those figures are a regression signal for that runner, not a portable throughput claim.

## Range reads

HTTP range requests select intersecting manifest entries before block retrieval. PostgreSQL applies the byte-span predicate in the manifest query; filesystem manifests scan only their lightweight entry records. The CAS then fetches and verifies only selected bounded blocks. A request near the end of a large blob therefore avoids object-store reads, digest work, and network transfer for every preceding block.

Selected blocks are still verified in full before any requested bytes from that block are emitted. Range efficiency does not weaken the content-addressed integrity check, and peak ordered-prefetch memory remains bounded by `maxInFlight * 16 MiB`.

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

`/api/stats` and `/metrics` expose process-local ingest, byte-weighted CAS reuse, HTTP request, error, and latency observations. They reset on restart and do not establish durable capacity or a service-level objective. The reuse ratio reports logical block bytes whose write Graviton avoided; it does not include replication, erasure coding, compression, metadata, or allocator overhead. Backend physical-utilization metrics and retained dashboards remain deployment work.
