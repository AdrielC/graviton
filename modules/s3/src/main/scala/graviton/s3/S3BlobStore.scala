package graviton.s3

import graviton.*
import zio.*
import zio.stream.*
import io.minio.MinioClient
import io.minio.{GetObjectArgs, PutObjectArgs, RemoveObjectArgs}
import java.io.ByteArrayInputStream

final class S3BlobStore(
  client: MinioClient,
  bucket: String,
  val id: BlobStoreId,
) extends BlobStore:
  private def keyPath(key: BlockKey): String = key.hash.hex.bytes

  def status: UIO[BlobStoreStatus] = ZIO.succeed(BlobStoreStatus.Operational)

  def read(
    key: BlockKey,
    range: Option[ByteRange] = None,
  ): IO[Throwable, Option[Bytes]] =
    val builder = GetObjectArgs.builder.bucket(bucket).`object`(keyPath(key))
    range.foreach { case ByteRange(start, end) =>
      builder.offset(start).length(end - start)
    }
    ZIO
      .attemptBlocking(client.getObject(builder.build()))
      .map(is => Some(Bytes(ZStream.fromInputStream(is).mapError(e => e: Throwable))))
      .catchSome {
        case e: io.minio.errors.ErrorResponseException if e.errorResponse().code() == "NoSuchKey" =>
          ZIO.succeed(None)
      }

  def write(key: BlockKey, data: Bytes): IO[Throwable, Unit] =
    for
      arr <- data.runCollect.map(_.toArray)
      _   <- ZIO.attemptBlocking(
               client.putObject(
                 PutObjectArgs.builder
                   .bucket(bucket)
                   .`object`(keyPath(key))
                   .stream(new ByteArrayInputStream(arr), arr.length, -1)
                   .build()
               )
             )
    yield ()

  def delete(key: BlockKey): IO[Throwable, Boolean] =
    ZIO.attemptBlocking {
      client.removeObject(
        RemoveObjectArgs.builder.bucket(bucket).`object`(keyPath(key)).build()
      )
      true
    }

object S3BlobStore:
  def layer(
    client: MinioClient,
    bucket: String,
    id: String = "s3",
  ): ZLayer[Any, Nothing, BlobStore] =
    ZLayer.succeed(
      new S3BlobStore(client, bucket, BlobStoreId(id)): BlobStore
    )
