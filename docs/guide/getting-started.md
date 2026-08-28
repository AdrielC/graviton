# Quickstart

Run a persistent filesystem store, upload a file as a stream, and verify the stored bytes. PostgreSQL, MinIO, and Docker are not required for this path.

## Requirements

- Java 21 or newer
- Git
- `curl` and `jq` for the HTTP example

## Get the source

```bash
git clone https://github.com/AdrielC/graviton.git
cd graviton
./sbt compile
```

## Start Graviton

Enable the built-in operator console and start the filesystem-backed server:

```bash
GRAVITON_CONSOLE_ENABLED=true ./sbt "server/run"
```

The process listens on `127.0.0.1:8081`, stores blocks and manifests below `.graviton/`, and exposes the local console at [http://127.0.0.1:8081/console](http://127.0.0.1:8081/console).

::: warning Local development only
The console requires security-disabled mode and binds to loopback by default. Do not expose this configuration to an untrusted network.
:::

## Upload and verify a file

Keep the server running. In another terminal:

```bash
BLOB_ID="$(
  curl -fsS \
    -H "Content-Type: application/octet-stream" \
    -X POST \
    --data-binary @README.md \
    http://127.0.0.1:8081/api/v1/blobs \
  | jq -r '.blob.id'
)"

curl -fsS -X POST \
  "http://127.0.0.1:8081/api/v1/blobs/$BLOB_ID/verify" \
  | jq .

curl -fsS \
  "http://127.0.0.1:8081/api/v1/blobs/$BLOB_ID" \
  --output /tmp/graviton-readme.md

cmp README.md /tmp/graviton-readme.md
```

The request body and response body stay streaming. Graviton does not materialize the complete file in application memory.

## Prove restart durability

Stop the server, start it again with the same command, and repeat the download. The manifest and blocks survive the process restart.

You can also run the repository's complete cross-process proof:

```bash
./scripts/verify-local-lifecycle.sh
```

The script runs ingest, stat, get, and verify in separate CLI JVMs, then compares the retrieved bytes with the source.

## Use the Scala API

The durable facade accepts and returns streams:

```scala
import graviton.runtime.Graviton
import zio.*
import zio.stream.*

import java.nio.file.{Files, Paths}

object Example extends ZIOAppDefault:
  override def run =
    for
      graviton  <- Graviton.fs(Paths.get(".graviton"))
      write     <- graviton.ingestFile(Paths.get("README.md"))
      _         <- graviton.stream(write.key).run(ZSink.fromFileName("/tmp/graviton-README.md"))
      identical <- ZIO.attempt(
                     Files.mismatch(
                       Paths.get("README.md"),
                       Paths.get("/tmp/graviton-README.md")
                     ) == -1L
                   )
      _         <- Console.printLine(s"${write.key.bits.render} identical=$identical")
    yield ()
```

Neither ingest nor retrieval collects the file.

## Choose the next path

- [Run locally](./run-locally.md) for the console, CLI, HTTP, gRPC, and 2-node Shardcake topology
- [Scala SDK](./scala-sdk.md) for typed client and embedded-runtime APIs
- [Storage backends](./storage-backends.md) for filesystem, S3-compatible, and PostgreSQL compositions
- [Configuration reference](./configuration-reference.md) for every server setting and default
- [Production readiness](../ops/production-readiness.md) for implemented gates and deployment boundaries
- [CAS Playground](../cas-playground.md) to inspect content IDs and repeated blocks in the browser
