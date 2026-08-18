package graviton.backend.s3

import graviton.core.locator.BlobLocator
import graviton.runtime.stores.MutableObjectStore
import zio.*
import zio.stream.*

final class S3MutableObjectStore extends S3ImmutableObjectStore with MutableObjectStore:
  override def put(locator: BlobLocator): ZSink[Any, Throwable, Byte, Nothing, Unit] =
    ZSink.foreach[Any, Throwable, Byte](_ => ZIO.unit)

  override def delete(locator: BlobLocator): ZIO[Any, Throwable, Unit]              = ZIO.unit
  override def copy(src: BlobLocator, dest: BlobLocator): ZIO[Any, Throwable, Unit] = ZIO.unit
