# Graviton API Documentation

Welcome to the Graviton API documentation. This documentation is automatically generated from the source code.

## Modules

- **graviton-core**: Pure domain types, hashing utilities, ranges, and manifests
- **graviton-streams**: ZIO Stream combinators and chunking pipelines
- **graviton-runtime**: Service ports, policies, and runtime abstractions
- **graviton-grpc**: gRPC service implementations
- **graviton-http**: HTTP/REST API endpoints
- **graviton-s3**: AWS S3 backend implementation
- **graviton-pg**: PostgreSQL backend implementation
- **graviton-rocks**: RocksDB backend implementation
- **graviton-server**: Application server and wiring

## Getting Started

Start by exploring:
- `graviton.core.BinaryKey` — Content-addressable keys
- `graviton.core.RangeSet` — Byte range algebra
- `graviton.runtime.BlobStore` — Primary storage interface
- `graviton.streams.Chunker` — Content-defined chunking

## External Resources

- [Full Documentation](https://adrielc.github.io/graviton/)
- [GitHub Repository](https://github.com/AdrielC/graviton)
- [Getting Started Guide](https://adrielc.github.io/graviton/guide/getting-started)
