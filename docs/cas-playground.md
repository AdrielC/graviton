---
layout: doc
title: CAS Playground
---

# CAS Playground

Type or paste content below. VitePress loads the compiled `graviton-shared` Scala.js module, which converts the exact UTF-8 bytes, splits them at the selected fixed boundary, computes SHA-256 through Web Crypto, formats Graviton content IDs, and identifies repeated blocks.

::: tip Real computation, local scope
Every byte count and digest is computed from your input. Nothing is randomized or supplied by a sample API. The results stay in browser memory and are not persisted to a Graviton server. The input is capped at 2,048 UTF-16 code units before encoding and then refined to at most 8,192 bytes before any complete byte value is materialized.
:::

## Shared library boundary

`graviton-shared` cross-compiles the analyzer, refined payload and digest types, block-size validation, content-ID formatting, and duplicate detection for JVM and Scala.js. The platform implementations deliberately keep the cryptographic primitive native:

| Target | SHA-256 implementation | Verification |
| --- | --- | --- |
| JVM | Java Cryptography Architecture `MessageDigest` | Shared known-vector, empty-input, deduplication, and rejection tests |
| Scala.js | Browser or Node Web Crypto `SubtleCrypto.digest` | The same shared contract suite plus a linked-module invocation |

This bounded API is for interactive and control-plane use. The Graviton server does not collect an upload into it. Production ingest and retrieval stay streaming for payloads far larger than the playground limit.

<PipelinePlayground />

## From worksheet to durable storage

The browser lab makes content addressing inspectable without requiring a hosted backend. Its content ID is computed locally, not returned by a server. To store the bytes durably and obtain the authoritative persisted manifest, start Graviton locally and use the [Connect Your Server console](./demo.md), CLI, or HTTP API.

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
