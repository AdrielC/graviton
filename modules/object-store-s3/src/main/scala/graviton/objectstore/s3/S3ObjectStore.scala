package graviton.objectstore.s3

import graviton.objectstore.*
import graviton.ranges.ByteRange
import io.minio.*
import io.minio.errors.ErrorResponseException
import _root_.zio.*
import _root_.zio.stream.*
import java.io.ByteArrayInputStream
import scala.jdk.CollectionConverters.*

final class S3ObjectStore(
  client: MinioClient,
  bucket: String,
) extends ObjectStore:

  private def normalise(path: ObjectPath): String =
    val key = path.asString
    if key.isEmpty then key else key

  def head(path: ObjectPath): IO[ObjectStoreError, Option[ObjectMetadata]] =
    val key = normalise(path)
    ZIO
      .attemptBlocking(client.statObject(StatObjectArgs.builder().bucket(bucket).`object`(key).build()))
      .map { stat =>
        val userMeta = Option(stat.userMetadata()).map(_.asScala.toMap).getOrElse(Map.empty)
        Some(
          ObjectMetadata(
            stat.size(),
            Option(stat.etag()),
            Option(stat.lastModified()).map(_.toInstant),
            Option(stat.contentType()),
            userMeta,
          )
        )
      }
      .catchSome { case _: ErrorResponseException => ZIO.succeed(None) }
      .mapError(ObjectStoreError.fromThrowable)

  def list(prefix: ObjectPath, recursive: Boolean): ZStream[Any, ObjectStoreError, ListedObject] =
    val args =
      ListObjectsArgs
        .builder()
        .bucket(bucket)
        .prefix(normalise(prefix))
        .recursive(recursive)
        .build()
    ZStream
      .fromIteratorZIO(
        ZIO
          .attemptBlocking(client.listObjects(args).iterator().asScala)
          .mapError(ObjectStoreError.fromThrowable)
      )
      .mapError(ObjectStoreError.fromThrowable)
      .mapZIO { result =>
        ZIO
          .attempt {
            val obj = result.get()
            ListedObject(ObjectPath(obj.objectName()), obj.size(), obj.isDir)
          }
          .mapError(ObjectStoreError.fromThrowable)
      }

  def get(path: ObjectPath, range: Option[ByteRange]): ZStream[Any, ObjectStoreError, Byte] =
    val key     = normalise(path)
    val builder = GetObjectArgs.builder().bucket(bucket).`object`(key)
    range.foreach { r =>
      builder.offset(r.startValue).length(r.lengthValue)
    }
    ZStream
      .fromInputStreamZIO(
        ZIO.attemptBlockingIO(client.getObject(builder.build()))
      )
      .mapError(ObjectStoreError.fromThrowable)

  private def collectBytes(data: ZStream[Any, Throwable, Byte]): IO[ObjectStoreError, Array[Byte]] =
    data.runCollect
      .map(_.toArray)
      .mapError(ObjectStoreError.fromThrowable)

  def put(path: ObjectPath, data: ZStream[Any, Throwable, Byte], metadata: PutMetadata): IO[ObjectStoreError, Unit] =
    val key = normalise(path)
    collectBytes(data).flatMap { arr =>
      ZIO
        .attemptBlocking {
          val builder =
            PutObjectArgs
              .builder()
              .bucket(bucket)
              .`object`(key)
              .stream(new ByteArrayInputStream(arr), arr.length, -1)
          metadata.contentType.foreach(builder.contentType)
          if metadata.userMetadata.nonEmpty then
            val _ = builder.userMetadata(metadata.userMetadata.asJava)
          client.putObject(builder.build())
        }
        .unit
        .mapError(ObjectStoreError.fromThrowable)
    }

  def putMultipart(path: ObjectPath, parts: ZStream[Any, Throwable, Byte], metadata: PutMetadata): IO[ObjectStoreError, Unit] =
    // For now we reuse put; MinIO SDK handles multi-part internally for large payloads.
    put(path, parts, metadata)

  def delete(path: ObjectPath): IO[ObjectStoreError, Boolean] =
    val key = normalise(path)
    ZIO
      .attemptBlocking {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(key).build())
        true
      }
      .mapError(ObjectStoreError.fromThrowable)

  def copy(from: ObjectPath, to: ObjectPath, options: CopyOptions): IO[ObjectStoreError, Unit] =
    val src = normalise(from)
    val dst = normalise(to)
    (if options.overwrite then ZIO.unit
     else
       head(to).flatMap {
         case Some(_) => ZIO.fail(ObjectStoreError.Unexpected(s"destination ${to.asString} already exists", None))
         case None    => ZIO.unit
       }
    ) *>
      ZIO
        .attemptBlocking {
          val builder = CopyObjectArgs
            .builder()
            .bucket(bucket)
            .`object`(dst)
            .source(CopySource.builder().bucket(bucket).`object`(src).build())
          client.copyObject(builder.build())
        }
        .unit
        .mapError(ObjectStoreError.fromThrowable)

object S3ObjectStore:
  def layer(client: MinioClient, bucket: String): ULayer[ObjectStore] =
    ZLayer.succeed(new S3ObjectStore(client, bucket))
