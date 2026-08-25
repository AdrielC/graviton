---
layout: doc
title: CAS Playground
---

# CAS Playground

Type or paste content below. The playground converts the exact UTF-8 bytes, splits them at the selected fixed boundary, computes real SHA-256 digests with the browser Web Crypto API, formats Graviton content IDs, and identifies repeated blocks.

::: tip Real computation, local scope
Every byte count and digest is computed from your input. Nothing is randomized or supplied by a sample API. The results stay in browser memory and are not persisted to a Graviton server.
:::

<PipelinePlayground />

## From worksheet to durable storage

The browser lab makes content addressing inspectable without requiring a hosted backend. To store the bytes durably, start Graviton locally and use the [Connect Your Server console](./demo.md), CLI, or HTTP API.

```bash
GRAVITON_FS_ROOT=/tmp/graviton-data \
GRAVITON_HTTP_PORT=8081 \
./sbt "server/run"
```

The server can use fixed, FastCDC, or delimiter chunking depending on configuration. Its returned manifest is authoritative for persisted data.

## Experiments worth trying

1. Load repeated blocks and confirm that identical byte ranges have identical content IDs.
2. Change one byte and observe the SHA-256 avalanche effect on that block and the blob ID.
3. Increase the block size and watch manifest cardinality decrease.
4. Paste Unicode text and compare character count with the actual UTF-8 byte count.

## Continue

- [Pipeline Explorer](./pipeline-explorer.md)
- [Chunking Strategies](./ingest/chunking.md)
- [Transducer Algebra](./core/transducers.md)
- [HTTP API](./api/http.md)
