package graviton.s3

import graviton.*
import zio.*
import zio.stream.*
import zio.aws.s3.S3

final class S3BlobStore(client: S3, bucket: String, val id: BlobStoreId) extends BlobStore:
  def status: UIO[BlobStoreStatus] = ZIO.succeed(BlobStoreStatus.Operational)

  def read(key: BlockKey): IO[Throwable, Option[Bytes]] =
    ZIO.fail(new NotImplementedError("S3 read not implemented"))

  def write(key: BlockKey, data: Bytes): IO[Throwable, Unit] =
    ZIO.fail(new NotImplementedError("S3 write not implemented"))

  def delete(key: BlockKey): IO[Throwable, Boolean] =
    ZIO.fail(new NotImplementedError("S3 delete not implemented"))

object S3BlobStore:
  def layer(bucket: String, id: String = "s3"): ZLayer[S3, Nothing, BlobStore] =
    ZLayer.fromFunction { (client: S3) => new S3BlobStore(client, bucket, BlobStoreId(id)): BlobStore }
