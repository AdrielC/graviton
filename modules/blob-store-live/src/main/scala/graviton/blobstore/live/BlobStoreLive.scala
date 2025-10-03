package graviton.blobstore.live

import graviton.*
import graviton.objectstore.*
import graviton.ranges.ByteRange
import graviton.Bytes
import zio.*

final class BlobStoreLive(
  objectStore: ObjectStore,
  keyToPath: BlockKey => ObjectPath,
  val id: BlobStoreId,
) extends BlobStore:
  private def mapError[A](io: IO[ObjectStoreError, A]): IO[Throwable, A] =
    io.mapError(err => err: Throwable)

  def status: UIO[BlobStoreStatus] = ZIO.succeed(BlobStoreStatus.Operational)

  def read(
    key: BlockKey,
    range: Option[ByteRange] = None,
  ): IO[Throwable, Option[Bytes]] =
    val path = keyToPath(key)
    mapError(objectStore.head(path)).flatMap {
      case None    => ZIO.succeed(None)
      case Some(_) =>
        val stream = objectStore.get(path, range).mapError(err => err: Throwable)
        ZIO.succeed(Some(Bytes(stream)))
    }

  def write(key: BlockKey, data: Bytes): Task[Unit] =
    mapError(objectStore.put(keyToPath(key), data))

  def delete(key: BlockKey): IO[Throwable, Boolean] =
    mapError(objectStore.delete(keyToPath(key)))

object BlobStoreLive:
  def forHashPrefix(objectStore: ObjectStore, prefixDepth: Int = 2, id: String = "live"): BlobStoreLive =
    val builder: BlockKey => ObjectPath = { key =>
      val hex      = key.hash.hex
      val segments = hex.grouped(2).take(prefixDepth).toList
      val prefix   = segments.mkString("/")
      val path     = if prefix.nonEmpty then s"$prefix/$hex" else hex
      ObjectPath(path)
    }
    new BlobStoreLive(objectStore, builder, BlobStoreId(id))
