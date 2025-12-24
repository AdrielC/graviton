package graviton.runtime.stores

import graviton.core.attributes.*
import graviton.core.bytes.*
import graviton.core.keys.*
import graviton.core.locator.*
import graviton.core.model.ByteConstraints
import graviton.runtime.model.*
import zio.*
import zio.stream.*

import java.time.Instant
import scala.collection.immutable.Map
import java.util.concurrent.TimeUnit

final class InMemoryBlobStore private (
  blobs: Ref[Map[BinaryKey, StoredBlob]],
  scheme: String,
  bucket: String,
) extends BlobStore:

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink
      .foldLeftChunks[Byte, ChunkBuilder[Byte]](ChunkBuilder.make[Byte]()) { (builder, chunk) =>
        builder ++= chunk
        builder
      }
      .mapZIO(builder => persist(builder.result(), plan))
      .ignoreLeftover

  override def get(key: BinaryKey): ZStream[Any, Throwable, Byte] =
    ZStream
      .fromZIO(
        blobs.get.flatMap { map =>
          ZIO
            .fromOption(map.get(key))
            .mapError(_ => new NoSuchElementException(s"Blob ${key.bits.digest.value} not found"))
        }
      )
      .flatMap(blob => ZStream.fromChunk(blob.bytes))

  override def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]] =
    blobs.get.map(_.get(key).map(_.stat))

  override def delete(key: BinaryKey): ZIO[Any, Throwable, Unit] =
    blobs.update(_ - key).unit

  private def persist(bytes: Chunk[Byte], plan: BlobWritePlan): IO[Throwable, BlobWriteResult] =
    for
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
      algo    = hasher.algo
      _       = hasher.update(bytes.toArray)
      digest <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
      bits   <- ZIO
                  .fromEither(KeyBits.create(algo, digest, bytes.length.toLong))
                  .mapError(msg => new IllegalArgumentException(msg))
      key    <- ZIO.fromEither(BinaryKey.blob(bits)).mapError(msg => new IllegalArgumentException(msg))
      size   <- ZIO
                  .fromEither(ByteConstraints.refineFileSize(bytes.length.toLong))
                  .mapError(msg => new IllegalArgumentException(msg))
      count  <- ZIO
                  .fromEither(ByteConstraints.refineChunkCount(deriveChunkCount(bytes.length)))
                  .mapError(msg => new IllegalArgumentException(msg))
      attrs   = plan.attributes
                  .confirmSize(size)
                  .confirmChunkCount(count)
      locator = plan.locatorHint.getOrElse(defaultLocator(key))
      now    <- Clock.currentTime(TimeUnit.MILLISECONDS)
      stat    = BlobStat(size, digest, Instant.ofEpochMilli(now))
      stored  = StoredBlob(bytes, locator, attrs, stat)
      _      <- blobs.update(_.updated(key, stored))
    yield BlobWriteResult(key, locator, attrs)

  private def deriveChunkCount(length: Int): Long =
    if length <= 0 then 0L
    else ((length - 1) / ByteConstraints.MaxBlockBytes + 1).toLong

  private def defaultLocator(key: BinaryKey): BlobLocator =
    BlobLocator(scheme, bucket, key.bits.digest.hex.value)

private final case class StoredBlob(
  bytes: Chunk[Byte],
  locator: BlobLocator,
  attributes: BinaryAttributes,
  stat: BlobStat,
)

object InMemoryBlobStore:
  def make(bucket: String = "default", scheme: String = "memory"): UIO[InMemoryBlobStore] =
    Ref.make(Map.empty[BinaryKey, StoredBlob]).map(ref => new InMemoryBlobStore(ref, scheme, bucket))

  val layer: ULayer[BlobStore] =
    ZLayer.fromZIO(make())
