# Design Notes

This document captures additional design discussions and tradeoffs explored while building **Torrent**, the ZIO-native CAS system.

## Recommended Chunk Sizes

Chunk size is a critical parameter in CAS design. Choosing the right size balances deduplication granularity, storage efficiency, memory use, and recompression overhead.

### Suggested Defaults

```scala
val DefaultMaxChunkSize = 1 * 1024 * 1024 // 1 MB
val DefaultMinChunkSize = 32 * 1024       // 32 KB
val DefaultAvgChunkSize = 64 * 1024       // 64 KB
```

### 📊 Tradeoffs

| Chunk Size         | Pros                                        | Cons                                      |
|--------------------|---------------------------------------------|-------------------------------------------|
| Small (32–64 KB)   | High dedup granularity                      | More overhead, more CAS inserts           |
| Medium (128–512 KB)| Balanced, good for large PDFs               | Fewer dedup hits for small shared content |
| Large (1–4 MB)     | Lower hash/storage overhead                 | Deduplication less effective              |

Smaller chunks help when:
- Documents reuse similar assets (e.g. seals, stamps, footers)
- PDFs have consistent structure across versions

Larger chunks help when:
- Storing large scans, long records, or media files
- You want lower blob count and fewer insert calls

## ZSink.foldWeightedDecompose – Smart Chunk Splitting

Torrent uses `ZSink.foldWeightedDecompose` to perform:
- **Streaming accumulation** of bytes into a logical chunk
- **Weighted cost tracking** using chunk size
- **Dynamic decomposition** of oversized chunks using semantic heuristics

### Usage

```scala
ZSink.foldWeightedDecompose(Chunk.empty[Byte])(
  costFn = (acc, next) => acc.length + next.length,
  max = 1024 * 1024,
  decompose = RollingHashChunker.findSplitPoints
)((acc, next) => acc ++ next)
```

This allows Torrent to:
- Emit smart-aligned boundaries
- Split overly large blocks at good points
- Avoid mid-token fragmentation

### Token-Aware Future Plan

`costFn` can be enhanced to:
- Penalize splitting mid-token
- Favor splits near known structural markers (`endstream`, newline, etc.)
- Adjust weight dynamically as the buffer nears limit

## Decompression Strategy

Torrent uses `/Length` and `/Filter` in PDF streams to decide:
- Should this `stream` be decompressed?
- Is it likely to yield deduplicated chunks?

### Decision Heuristic

```scala
def shouldDecompress(length: Int, filter: String): Boolean =
  filter match
    case "/FlateDecode" => length >= 1024
    case "/DCTDecode"   => false
    case _              => false
```

Estimated decompressed size helps avoid wasteful chunking of tiny streams.

## Structural Token Boundaries

When parsing documents like PDFs, chunking must **respect format boundaries**.

Example:

- You must never split across `stream ... endstream`
- Chunks inside must match `/Length` exactly or decompression fails

### RollingHash + Token Fence

Torrent may eventually use a hybrid model:
- Rolling hash to propose chunk cuts
- Finalizer pass to adjust toward known tokens (`endobj`, `}` etc.)

## Partial Token Handling (Planned)

A `TokenContext` may be threaded through `foldWeightedDecompose`:
- Tracks trailing bytes
- Identifies mid-token state
- Affects chunk cost and decision-making

## Integration Notes

- `RollingHashChunker.pipeline` is designed to be drop-in for `ZStream[Byte]`
- `ZSink.foldWeightedDecompose` can emit exact boundaries via custom `ChunkPolicy`
- PDF-aware tokenizer will provide `ZStream[PdfStream]` with correct offsets

## Summary

- Prefer ~64–256 KB chunks for dedup effectiveness
- Use rolling hash chunker by default
- Split with `foldWeightedDecompose` for precise streaming control
- Use token-aware logic for exact format fidelity (especially for PDFs)
- Decompress selectively based on filter/length heuristics

Torrent will evolve with smarter folding, trie-based permission resolution, and plugin chunk policies.