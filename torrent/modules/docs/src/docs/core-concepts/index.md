# Core Concepts

Torrent is built around several key concepts that enable efficient and reliable content storage:

## Key Concepts

- [Content-Addressable Storage](content-addressable-storage.md): Store and retrieve content using cryptographic hashes
- [Binary Streaming](binary-streaming.md): Efficient streaming of binary data
- [Chunking Strategies](chunking-strategies.md): Smart content deduplication

## Architecture

Torrent is designed with modularity in mind:

- Core interfaces for content storage
- Multiple backend implementations
- Pluggable chunking strategies
- Extensible content detection

## Design Goals

- Type-safe API
- High performance
- Data integrity
- Storage efficiency
- Backend flexibility 