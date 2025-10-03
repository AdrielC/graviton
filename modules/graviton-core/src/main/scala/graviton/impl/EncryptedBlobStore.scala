package graviton.impl

import graviton.*
import graviton.ranges.ByteRange
import zio.*
import zio.stream.*

final class EncryptedBlobStore(delegate: BlobStore, encryption: Encryption) extends BlobStore:

  def id: BlobStoreId = delegate.id

  def status: UIO[BlobStoreStatus] = delegate.status

  def read(
    key: BlockKey,
    range: Option[ByteRange] = None,
  ): IO[Throwable, Option[Bytes]] =
    delegate.read(key, None).flatMap {
      case None        => ZIO.succeed(None)
      case Some(bytes) =>
        bytes.runCollect.flatMap { encrypted =>
          encryption.decrypt(key.hash, encrypted).map { plain =>
            val sliced = range match
              case Some(ByteRange(start, end)) =>
                plain.drop(start.toInt).take((end - start).toInt)
              case None                        => plain
            Some(Bytes(ZStream.fromChunk(sliced)))
          }
        }
    }

  def write(key: BlockKey, data: Bytes): IO[Throwable, Unit] =
    data.runCollect.flatMap { plain =>
      encryption.encrypt(key.hash, plain).flatMap { encrypted =>
        delegate.write(key, Bytes(ZStream.fromChunk(encrypted)))
      }
    }

  def delete(key: BlockKey): IO[Throwable, Boolean] = delegate.delete(key)

object EncryptedBlobStore:
  val layer: ZLayer[BlobStore & Encryption, Nothing, BlobStore] =
    ZLayer.fromFunction(new EncryptedBlobStore(_, _))
