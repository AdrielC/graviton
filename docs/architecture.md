# Architecture

Graviton separates pure domain logic from effectful runtime code.

## High-Level System View

```mermaid
flowchart LR
  classDef client fill:#dff4ff,stroke:#0077b6,color:#002233;
  classDef transport fill:#f4f0ff,stroke:#6a4c93,color:#1a1326;
  classDef runtime fill:#fef3c7,stroke:#d97706,color:#78350f;
  classDef backend fill:#ecfdf3,stroke:#16a34a,color:#064e3b;
  classDef ops fill:#fdf2f8,stroke:#db2777,color:#831843;

  subgraph Clients["Clients"]
    cli["CLI"]
    sdks["SDKs"]
    integ["Integrations"]
  end

  subgraph Transports["Protocol Surfaces"]
    http["HTTP Gateway"]
    grpc["gRPC Gateway"]
  end

  subgraph Runtime["Runtime Ports & Services"]
    ingest["Ingest Service"]
    retrieve["Retrieval Service"]
    manifest["Manifest Builder"]
    metrics["Metrics Facade"]
  end

  subgraph Backends["Storage Backends"]
    s3["S3 Blob Store"]
    pg["PostgreSQL Metadata"]
    rocks["RocksDB Hot Cache"]
  end

  observability["Prometheus + Structured Logs"]

  cli --> http
  cli --> grpc
  sdks --> http
  sdks --> grpc
  integ --> http
  integ --> grpc
  http --> ingest
  grpc --> ingest
  http --> retrieve
  grpc --> retrieve
  ingest --> manifest
  manifest --> pg
  ingest -->|Blocks| s3
  retrieve -->|Cache| rocks
  metrics --> observability
  ingest --> metrics
  retrieve --> metrics

  class cli,sdks,integ client
  class http,grpc transport
  class ingest,retrieve,manifest,metrics runtime
  class s3,pg,rocks backend
  class observability ops
```

## Core

`graviton-core` contains purely functional data structures and codecs:

- Hashing utilities (`HashAlgo`, `Digest`, `MultiHasher`).
- Binary identifiers (`BinaryKey`, `KeyBits`, `ViewTransform`).
- Locator abstractions (`BlobLocator`, `LocatorStrategy`).
- Range utilities (`Bound`, `Interval`, `Span`, `RangeSet`).
- Manifest encoders and decoders.
- Persistent union-find for replica placement.

## Streams

`graviton-streams` bridges the pure types with ZIO Streams by providing chunkers, hashing sinks, and reusable combinators for fan-out and scanning. These helpers orchestrate the content-defined chunking primitives from the `zio-blocks` submodule.

## Runtime

`graviton-runtime` defines the service ports consumed by the transports and backends. It holds policies, indexes, constraints, and metrics facades while remaining agnostic to concrete storage drivers.

## Protocol

- `graviton-proto`: protobuf contracts for gRPC and derived HTTP routes.
- `graviton-grpc`: zio-grpc services that implement uploads, blob retrieval, and admin features.
- `graviton-http`: zio-http routes exposing REST-style endpoints with JSON codecs derived from zio-schema.

## Backends

Each backend implements the runtime ports using specific technologies:

- `graviton-s3`: AWS SDK v2 object store with multipart uploads.
- `graviton-pg`: PostgreSQL based object and metadata stores.
- `graviton-rocks`: RocksDB based key-value primitives with metrics integration.

## Server

`graviton-server` assembles the runtime into a deployable binary. It wires configuration, selects backend layers, starts HTTP and gRPC frontends, coordinates multipart uploads via shardcake entities, and publishes Prometheus metrics.
