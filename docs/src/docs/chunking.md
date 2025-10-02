# Chunking Strategies

Graviton uses content‑defined chunking to maximize deduplication across related
files.  This document captures guidelines and design notes for selecting chunk
sizes and tuning the `RollingHashChunker`.

## Recommended Chunk Sizes

Choosing appropriate chunk sizes balances deduplication granularity, storage
overhead, and recompression cost.

```scala
val DefaultMaxChunkSize = 1 * 1024 * 1024 // 1 MB
val DefaultMinChunkSize = 32 * 1024       // 32 KB
val DefaultAvgChunkSize = 64 * 1024       // 64 KB
```

### Tradeoffs

| Chunk Size         | Pros                               | Cons                                   |
|--------------------|------------------------------------|----------------------------------------|
| Small (32–64 KB)   | High dedup granularity             | More overhead and more CAS inserts     |
| Medium (128–512 KB)| Balanced for large PDFs            | Fewer dedup hits for small shared data |
| Large (1–4 MB)     | Lower hash/storage overhead        | Deduplication less effective           |

Smaller chunks help when documents reuse common assets such as seals or
footers.  Larger chunks are better for big scans or media files where overhead
must be minimized.

## `ZSink.foldWeightedDecompose`

The `RollingHashChunker` leverages `ZSink.foldWeightedDecompose` to build
chunks while tracking cost:

```scala
ZSink.foldWeightedDecompose(Chunk.empty[Byte])(
  costFn = (acc, next) => acc.length + next.length,
  max = 1024 * 1024,
  decompose = RollingHashChunker.findSplitPoints
)((acc, next) => acc ++ next)
```

This provides:

- streaming accumulation of bytes into logical chunks
- weighted cost tracking using chunk size
- semantic decomposition of oversized chunks at stable boundaries

The cost function can evolve to penalize mid‑token splits or favor structural
markers like `endstream`.

## Decompression Strategy

When ingesting PDF streams, Graviton uses `/Length` and `/Filter` metadata to
decide whether decompression is worthwhile:

```scala
def shouldDecompress(length: Int, filter: String): Boolean =
  filter match
    case "/FlateDecode" => length >= 1024
    case "/DCTDecode"   => false
    case _               => false
```

Estimating the decompressed size avoids wasting time on tiny streams.

## Structural Token Boundaries

Chunking must respect format boundaries—for example, never splitting across
`stream ... endstream` in PDFs.  A hybrid approach can combine rolling hash cuts
with a final pass that nudges boundaries toward tokens like `endobj` or `}`.

## Integration Notes

- `RollingHashChunker.pipeline` plugs into any `ZStream[Byte]` source.
- `ZSink.foldWeightedDecompose` can emit exact boundaries via custom
  `ChunkPolicy` implementations.
- Token‑aware policies are planned to provide `ZStream[PdfStream]` with correct
  offsets for downstream processors.

## Summary

- Prefer 64–256 KB chunks for general‑purpose workloads.
- Use the rolling hash chunker by default.
- Split large buffers with `foldWeightedDecompose` for precise control.
- Decompress selectively based on filter and size heuristics.

These guidelines will continue to evolve as semantic chunkers and additional
storage policies are implemented.

