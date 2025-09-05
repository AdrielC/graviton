# Content-Addressable Storage

## Overview

Content-addressable storage (CAS) is a way of storing information so that it can be retrieved based on its content rather than its location. In Torrent, this means that identical content will always have the same address, enabling automatic deduplication and content verification.

## How It Works

1. Content is split into chunks using configurable strategies
2. Each chunk is hashed using cryptographic functions
3. Chunks are stored using their hashes as keys
4. Content is reassembled using a manifest of chunk hashes

## Benefits

- **Deduplication**: Identical content is stored only once
- **Integrity**: Content can be verified using its hash
- **Efficiency**: Only changed chunks need to be stored
- **Scalability**: Content can be distributed across storage nodes

## Example

```scala
import torrent._

val storage = TorrentStorage.create()

for {
  // Store content and get its key
  key <- storage.store("Hello, World!".getBytes)
  
  // Retrieve content using the key
  content <- storage.retrieve(key)
  
  // Verify content integrity
  isValid <- storage.verify(key, content)
} yield isValid
```

## Next Steps

- Learn about [Binary Streaming](../core-concepts/binary-streaming.md)
- Explore [Chunking Strategies](../core-concepts/chunking-strategies.md)
- Check the [API Reference](../api-reference/binary-store.md) 