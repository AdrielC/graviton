---
title: "Getting Started"
sidebar_label: "Getting Started"
---

> 🚀 New to Graviton? This guide walks through the core ideas, shows how to run the CLI, and points you at next steps once bytes are flowing.

## What is Graviton?

Graviton is a ZIO-powered content-addressable storage (CAS) engine. Instead of treating files as opaque blobs, Graviton slices incoming bytes into reusable **Blocks** and tracks them inside a **Manifest**. Each completed manifest represents a logical **Blob** that can be addressed deterministically by its content hash.

Graviton keeps track of where every block replica lives across configured storage backends—filesystem folders, S3 buckets, or other drivers. That means you can ingest data once and serve it reliably even if one replica disappears.

## Core Concepts

The storage model revolves around a few reusable building blocks:

- **Blocks** – Fixed-size or content-defined chunks of bytes. Blocks are hashed and deduplicated before they ever hit disk.
- **Manifests** – Ordered collections of block references plus blob-level metadata. Manifests describe how to reconstruct the original stream.
- **Blobs** – Logical files addressed by a `(hash, size, algorithm)` triple known as a `BlobKey`.
- **Stores** – Physical locations where block replicas are persisted. Graviton ships with filesystem and S3 stores out of the box.
- **Chunkers** – Pluggable strategies (fixed, FastCDC, anchored) that decide how streams are split into blocks.

For a deeper glossary covering invariants and replication guarantees, head to the [Concepts overview](../concepts.md).

## Install the CLI

The easiest way to experience Graviton is through its CLI. Download the latest release from GitHub and add it to your PATH:

```bash
curl -L https://github.com/graviton-storage/graviton/releases/latest/download/graviton-cli-x86_64.tar.gz \
  | tar -xz -C /usr/local/bin
```

> Replace the archive with the build that matches your operating system and CPU architecture.

Once the binary is on your PATH, verify the installation:

```bash
graviton --version
```

You should see the semantic version along with the Git commit hash embedded in the build.

## Configure a Store

Graviton needs somewhere to write blocks and manifests. Create a filesystem-backed store by adding a configuration file:

```hocon
# graviton.conf
stores {
  primary {
    type = "filesystem"
    root = "/var/lib/graviton/store"
  }
}
```

Point the CLI at this configuration via the `GRAVITON_CONFIG` environment variable:

```bash
export GRAVITON_CONFIG=/path/to/graviton.conf
```

The CLI will automatically create the directory structure the first time it writes data.

## Ingest Your First Blob

With a store configured, ingest any file to see the CAS workflow in action:

```bash
graviton put README.md
```

The command streams the file, chunks it, writes blocks to the filesystem, and prints a `BlobKey` like:

```
BlobKey(algorithm = blake3, size = 16384, hash = 8e0d…)
```

Save this key—it is your handle for future reads.

## Retrieve Data


```scala mdoc:silent
import graviton.impl.InMemoryBinaryStore
import scala.annotation.nowarn

@nowarn("msg=unused value of type zio.ZIO")
val runDemo = for
  store <- InMemoryBinaryStore.make()
  data  <- storeAndFetch(store)
yield data
```

Graviton resolves the manifest, streams each block from the configured store, and reconstructs the file.

## Next Steps

- Explore the [HTTP gateway](../interfaces/http-gateway.md) for remote ingest and retrieval.
- Wire up monitoring using the [metrics guide](../operations/metrics.md).
- Learn how manifests evolve and how replication is enforced in the [replication model docs](../concepts.md#replication).

Questions or feedback? Join the discussion in the Graviton issue tracker and let us know what workflows you want to build on top of CAS storage.
