# Quick Start

## Basic Usage

```scala mdoc:silent
import zio.*
import zio.stream.*
import graviton.*
import graviton.impl.InMemoryBinaryStore

def storeAndFetch(store: BinaryStore): ZIO[Any, Throwable, Option[Bytes]] =
  for
    id <- ZStream.fromIterable("Hello, Graviton!".getBytes)
            .run(store.put)
    data <- store.get(id)
  yield data
```

The function above writes a stream of bytes to an existing `BinaryStore` and then retrieves the stored content.

```scala mdoc:silent
def runDemo =
  for
    store <- InMemoryBinaryStore.make()
    data  <- storeAndFetch(store)
  yield data
```

The `runDemo` program creates an in-memory store, writes a greeting, and reads it back.

## Next Steps

- Explore the [Binary Store design](../binary-store.md) for more details.
- Experiment with different [chunking strategies](../chunking.md).
- Check the [examples](../examples/index.md) for CLI and HTTP demos.
