package graviton.metrics

import graviton.{BinaryId, BinaryStore, ByteRange}
import graviton.core.BinaryAttributes
import zio.*
import zio.stream.*
import Metrics.*

final case class MetricsBinaryStore(underlying: BinaryStore)
    extends BinaryStore:
  override def put(
      attrs: BinaryAttributes,
      chunkSize: Int
  ): ZSink[Any, Throwable, Byte, Nothing, BinaryId] =
    ZSink.unwrapScoped {
      Clock.nanoTime.map { start =>
        underlying.put(attrs, chunkSize).mapZIO { id =>
          for
            end <- Clock.nanoTime
            _ <- putCount.increment
            _ <- putLatency.update(Duration.fromNanos(end - start))
          yield id
        }
      }
    }

  override def get(
      id: BinaryId,
      range: Option[ByteRange]
  ): IO[Throwable, Option[graviton.Bytes]] =
    for
      start <- Clock.nanoTime
      res <- underlying.get(id, range)
      end <- Clock.nanoTime
      _ <- getCount.increment
      _ <- getLatency.update(Duration.fromNanos(end - start))
    yield res

  override def delete(id: BinaryId): IO[Throwable, Boolean] =
    for
      start <- Clock.nanoTime
      res <- underlying.delete(id)
      end <- Clock.nanoTime
      _ <- deleteCount.increment
      _ <- deleteLatency.update(Duration.fromNanos(end - start))
    yield res

  override def exists(id: BinaryId): IO[Throwable, Boolean] =
    underlying.exists(id)
