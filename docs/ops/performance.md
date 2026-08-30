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

The scheduled `Production qualification` workflow retains its complete record for 90 days. It generates a checksummed deterministic corpus outside the Git worktree: one 32 MiB base object, a sparse edit that preserves 24 of 32 fixed-size blocks, and one block repeated 32 times. The gate first proves fresh, partial-reuse, and duplicate transitions, then retains eight raw samples plus distributions for every workload. Generated-runner results are regression evidence for that exact commit and topology, not a portable performance claim.

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

The tenant-aware runtime resolves its `TenantStoreProvider` once per logical stream operation. A structural regression test uploads and downloads an 8 MiB stream and observes exactly two resolutions total. Another test resolves 4,096 tenant policies twice in parallel and admits 1,024 tenants through the bounded registries, proving cache reuse and removal of one process-wide tenant lock. These establish hot-path shape and cardinality behavior, not a universal throughput or customer-count claim. Run the retained benchmark suite with the real catalog, authorization, quotas, HikariCP bounds, tenant distribution, and concurrency before setting rollout limits.

The packaged server creates one primary PostgreSQL pool per process and shares it across manifests, tenant policy, retained quotas, resumable state, audit, and JDBC authorization. Cold policy resolution is single-flight per tenant and the database lookup runs outside the bounded shard lock, so one slow tenant does not convoy unrelated tenants that hash to the same shard. Multi-tenant operations in one deployment cell also share one advisory-lock coordinator, so active tenants do not each pin a pool connection for the lifetime of a byte stream. Shardcake placement has a separate bounded pool because it may use a different database. Size the sum of both pools across every node and manager below PostgreSQL or proxy connection limits. A pool protects the database from unbounded connection creation; it does not make an undersized database fast.

JDBC audit uses a fixed local lock-shard set plus PostgreSQL's per-organization transaction advisory lock. Historical organization count therefore does not create a process-resident semaphore map. Unrelated organizations can occasionally share one local shard, while the database lock remains the authoritative chain-serialization boundary.

The live-byte ceiling controlled by Graviton is:

```text
input queue chunks * I/O chunk bytes
+ block queue entries * chunker maximum block bytes
+ (2 * block write parallelism + 1) * chunker maximum block bytes
```

This excludes one caller-owned input chunk and the chunker's implementation-specific working set. The doubled write term covers an ordered prepared block and a replayable backend request body for every concurrent write, plus one block being produced. With the defaults and a 1 MiB fixed chunker, the admitted ceiling is 11,796,480 bytes per active ingest.

Parallel block writes mainly target object stores. The S3 adapter creates a new block with one conditional `PutObject`; it does not issue a speculative `HeadObject`. If the key already exists, the rejected conditional write is followed by a metadata-only `HeadObject` that proves the stored length, content key, and SHA-256 checksum. Missing or inconsistent proof metadata fails closed without downloading the object. Do not assume that increasing parallelism improves a local filesystem. Measure the selected backend and keep concurrency bounded.

The S3 adapter materializes each already-bounded CAS block once because the AWS synchronous request body requires a replayable body for checksums and retries. The admission formula includes one such block-size allowance per active write. The process-wide `TransferBudget` is mandatory in packaged server wiring, defaults to 512 MiB, and prevents an unsafe maximum-block and maximum-parallelism combination from accepting bytes. A standalone `S3BlobStore` or `S3MutableObjectStore` also reserves its complete 128 MiB adaptive part ceiling before pulling input.

The fixed 2+1 erasure adapter trades CPU and network for lower cross-target storage overhead. It stores 1.5 times the canonical block bytes before object metadata, compared with 3 times for three full replicas. XOR is linear in block size. A maximum-size 16 MiB block produces three 8 MiB shards. The codec itself retains at most 40 MiB of source and shard bytes for one active block. Parallel synchronous S3 request bodies raise the conservative maximum write allowance to 64 MiB. A convergence repair that retains read shards while rebuilding and writing a missing shard has a conservative 88 MiB maximum. Ordinary reads race the three failure domains and cancel the remaining fiber after any two verified shards arrive. Because the third bounded read may finish before cancellation, ordinary reconstruction has a conservative 48 MiB maximum. These are hard per-block ceilings, not expected default usage: the 1 MiB default block yields proportionally smaller allowances. Multiply the relevant bound by configured block-write concurrency before choosing heap limits. Ceph's own replication or erasure policy adds another independent cost layer.

The opt-in MinIO integration gate uploads the same 32 MiB stream twice with 1 MiB blocks, verifies the reconstructed content by streaming it through the hasher, asserts 32 fresh blocks followed by 32 duplicate blocks, and records both elapsed times in the CI log. Those figures are a regression signal for that runner, not a portable throughput claim.

## Range reads

HTTP range requests select intersecting manifest entries before block retrieval. PostgreSQL applies the byte-span predicate in the manifest query; filesystem manifests scan only their lightweight entry records. The CAS then fetches and verifies only selected bounded blocks. A request near the end of a large blob therefore avoids object-store reads, digest work, and network transfer for every preceding block.

Selected blocks are still verified in full before any requested bytes from that block are emitted. Range efficiency does not weaken the content-addressed integrity check, and peak ordered-prefetch memory remains bounded by `maxInFlight * 16 MiB`.

## Inline CAS versus resumable staging

The implemented upload endpoint performs CAS inline: it reads the stream once, sniffs a bounded prefix, selects a chunker, hashes the complete blob, hashes each bounded block, persists blocks with back pressure, and atomically publishes the manifest last. The response therefore contains the final content ID.

The implemented `/api/v1/uploads` protocol changes that cost shape to obtain durable recovery. Each bounded part is written once to filesystem or S3 staging. Commit then reads every part once, in ledger order, to validate the exact total, sniff media, choose the chunker, derive block and blob hashes, persist unique blocks, and publish the manifest. Staging therefore adds one complete temporary write and one complete temporary read; it cannot make CAS hashing free.

The advantage is operational rather than computational: acknowledged offsets survive process restart, repeated part IDs are idempotent, transient SDK retries replay only one Iron-bounded part, commit is leased and content-idempotent, and expiry cleans orphans. The response is not a temporary content ID. Only successful commit returns the final immutable blob key.

The current S3 staging adapter stores each client-defined part as one generic object and internally switches to adaptive multipart upload under its existing Iron-bounded buffer and abort finalizer. Staging and final CAS ingest share the server's process-wide transfer budget, so their conservative reservations cannot multiply past the configured ceiling. Graviton CAS blocks remain bounded to at most 16 MiB and default to 1 MiB, so bounded concurrent single-object block writes are still the appropriate CAS default rather than multipart upload per block.

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

`/api/stats` and `/metrics` expose process-local ingest, byte-weighted CAS reuse, HTTP request, error, latency, tenant resolution, admission, quota rejection, resumable session, replica placement, erasure reconstruction, write, repair, and repair-cycle observations. They reset on restart and do not establish durable capacity by themselves. Durable retained usage lives in PostgreSQL. The live rules and dashboard under `deploy/three-domain` provide a starting SLO view, while long-term retention and target-specific thresholds remain operator work. The reuse ratio reports logical block bytes whose write Graviton avoided; it does not include replication, erasure coding, compression, metadata, or allocator overhead.
