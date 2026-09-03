package graviton.runtime.stores

import graviton.core.attributes.*
import graviton.core.bytes.*
import graviton.core.keys.*
import graviton.core.locator.*
import graviton.core.model.ByteConstraints
import graviton.core.types.{LocatorBucket, LocatorScheme}
import graviton.core.types.{ChunkCount, FileSize}
import graviton.runtime.model.*
import zio.*
import zio.stream.*

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.collection.immutable.Map

/**
 * TEST-ONLY in-memory BlobStore.
 *
 * This must never be wired in production codepaths.
 */
final class InMemoryBlobStore private (
  blobs: Ref[Map[BinaryKey, StoredBlob]],
  scheme: LocatorScheme,
  bucket: LocatorBucket,
) extends BlobStore:

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink
      .foldLeftChunks[Byte, ChunkBuilder[Byte]](ChunkBuilder.make[Byte]()) { (builder, chunk) =>
        builder ++= chunk
        builder
      }
      .mapZIO(builder => persist(builder.result(), plan).mapError(StoreError.fromThrowable(StoreOperation.PutBlob)))

  override def get(key: BinaryKey.Blob): ZStream[Any, StoreError, Byte] =
    ZStream
      .fromZIO(
        blobs.get.flatMap { map =>
          ZIO
            .fromOption(map.get(key))
            .mapError(_ => StoreError.NotFound(StoreOperation.GetBlob, key))
        }
      )
      .flatMap(blob => ZStream.fromChunk(blob.bytes))

  override def stat(key: BinaryKey.Blob): IO[StoreError, Option[BlobStat]] =
    blobs.get.map(_.get(key).map(_.stat))

  override def inventoryPage(after: Option[InventoryCursor], limit: InventoryPageSize): IO[StoreError, InventoryPage[BlobListing]] =
    for
      anchor  <- ZIO
                   .fromEither(
                     after.fold[Either[String, Option[String]]](Right(None))(cursor =>
                       InventoryCursor.decode(cursor, InventoryNamespace.InMemory).map(Some(_))
                     )
                   )
                   .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
      entries <- blobs.get
      ordered  = entries.iterator.collect { case (key: BinaryKey.Blob, stored) => key -> stored }.toList.sortBy(_._1.bits.render)
      rows     = ordered.dropWhile { case (key, _) => anchor.exists(_ >= key.bits.render) }.take(limit.value + 1)
      items    = Chunk.fromIterable(rows.take(limit.value).map { case (key, stored) => BlobListing(key, stored.stat, blockCount = 1) })
      next    <- ZIO.foreach(rows.lift(limit.value - 1).filter(_ => rows.length > limit.value)) { case (key, _) =>
                   ZIO
                     .fromEither(InventoryCursor.encode(InventoryNamespace.InMemory, key.bits.render))
                     .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
                 }
    yield InventoryPage(items, next)

  override def inspect(key: BinaryKey.Blob): IO[StoreError, Option[BlobDescription]] =
    stat(key).map(_.map(value => BlobDescription(BlobListing(key, value, blockCount = 1), Chunk.empty)))

  override def delete(key: BinaryKey.Blob): IO[StoreError, Unit] =
    blobs.update(_ - key).unit

  override def healthCheck: IO[StoreError, Unit] = ZIO.unit

  private def persist(bytes: Chunk[Byte], plan: BlobWritePlan): IO[Throwable, BlobWriteResult] =
    for
      _              <- ZIO
                          .fromEither(plan.attributes.validate)
                          .mapError(msg => new IllegalArgumentException(s"Invalid binary attributes in BlobWritePlan: $msg"))
      _              <- ZIO
                          .fail(new IllegalArgumentException("Empty blobs are not supported (size must be > 0)"))
                          .when(bytes.isEmpty)
      hasher         <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
      algo            = hasher.algo
      _               = hasher.update(bytes)
      digest         <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
      bits           <- ZIO
                          .fromEither(KeyBits.fromLong(algo, digest, bytes.length.toLong))
                          .mapError(msg => new IllegalArgumentException(msg))
      key            <- ZIO.fromEither(BinaryKey.blob(bits)).mapError(msg => new IllegalArgumentException(msg))
      size           <- ZIO
                          .fromEither(FileSize.either(bytes.length.toLong))
                          .mapError(msg => new IllegalArgumentException(msg))
      count          <- ZIO
                          .fromEither(ChunkCount.either(deriveChunkCount(bytes.length)))
                          .mapError(msg => new IllegalArgumentException(msg))
      attrs           = plan.attributes
                          .confirmSize(size)
                          .confirmChunkCount(count)
      validatedAttrs <- ZIO
                          .fromEither(attrs.validate)
                          .mapError(msg => new IllegalStateException(s"Generated invalid confirmed attributes: $msg"))
      locator         = plan.locatorHint.getOrElse(defaultLocator(key))
      now            <- Clock.currentTime(TimeUnit.MILLISECONDS)
      stat            = BlobStat(size, digest, Instant.ofEpochMilli(now))
      stored          = StoredBlob(bytes, locator, validatedAttrs, stat)
      _              <- blobs.update(_.updated(key, stored))
    yield BlobWriteResult(key, locator, validatedAttrs)

  private def deriveChunkCount(length: Int): Long =
    if length <= 0 then 0L
    else ((length - 1) / ByteConstraints.MaxBlockBytes + 1).toLong

  private def defaultLocator(key: BinaryKey): BlobLocator =
    BlobLocator(scheme, bucket, graviton.core.types.LocatorPath.applyUnsafe(key.bits.digest.hex.value))

private final case class StoredBlob(
  bytes: Chunk[Byte],
  locator: BlobLocator,
  attributes: BinaryAttributes,
  stat: BlobStat,
)

object InMemoryBlobStore:
  def make(bucket: String = "default", scheme: String = "memory"): UIO[InMemoryBlobStore] =
    Ref.make(Map.empty[BinaryKey, StoredBlob]).map { ref =>
      val s = graviton.core.types.LocatorScheme.applyUnsafe(Option(scheme).getOrElse("").trim.toLowerCase)
      val b = graviton.core.types.LocatorBucket.applyUnsafe(Option(bucket).getOrElse("").trim)
      new InMemoryBlobStore(ref, s, b)
    }
