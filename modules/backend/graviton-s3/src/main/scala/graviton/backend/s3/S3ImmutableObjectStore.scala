package graviton.backend.s3

import graviton.core.locator.BlobLocator
import graviton.runtime.stores.{ImmutableObjectStore, StoreError, StoreOperation}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import zio.stream.ZStream
import zio.{Chunk, IO, Task, ZIO}

import scala.jdk.CollectionConverters.*

final case class S3ObjectStoreConfig(
  storage: S3Config,
  partSizeBytes: S3BlobStore.PartSize = S3BlobStore.PartSize.Default,
  scheme: String = "s3",
)

class S3ImmutableObjectStore protected[s3] (
  protected val client: S3Client,
  protected val config: S3ObjectStoreConfig,
) extends ImmutableObjectStore:

  override def head(locator: BlobLocator): IO[StoreError, Option[Long]] =
    target(locator)
      .flatMap { objectTarget =>
        val request = HeadObjectRequest.builder().bucket(objectTarget.bucket).key(objectTarget.key).build()
        ZIO
          .attemptBlocking(client.headObject(request))
          .map(response => Some(response.contentLength().longValue()))
          .catchSome { case error: S3Exception if S3BlobStore.isNotFound(error) => ZIO.succeed(None) }
      }
      .mapError(storeError(StoreOperation.HeadObject))

  override def list(prefix: String): ZStream[Any, StoreError, BlobLocator] =
    ZStream
      .paginateChunkZIO("") { continuationToken =>
        ZIO.attemptBlocking {
          val builder  = ListObjectsV2Request
            .builder()
            .bucket(config.storage.bucket)
            .prefix(prefixed(prefix))
          if continuationToken.nonEmpty then
            val _ = builder.continuationToken(continuationToken)
          val response = client.listObjectsV2(builder.build())
          val locators = Chunk.fromIterable(
            response
              .contents()
              .asScala
              .iterator
              .map { value =>
                BlobLocator
                  .from(config.scheme, config.storage.bucket, relative(value.key()))
                  .fold(message => throw new IllegalStateException(message), identity)
              }
              .toList
          )
          (locators, Option(response.nextContinuationToken()).filter(_ => response.isTruncated))
        }
      }
      .mapError(storeError(StoreOperation.ListObjects))

  override def get(locator: BlobLocator): ZStream[Any, StoreError, Byte] =
    ZStream
      .fromZIO(target(locator))
      .flatMap { objectTarget =>
        val request = GetObjectRequest.builder().bucket(objectTarget.bucket).key(objectTarget.key).build()
        ZStream
          .acquireReleaseWith(ZIO.attemptBlocking(client.getObject(request)))(stream => ZIO.attemptBlocking(stream.close()).orDie)
          .flatMap(stream => ZStream.fromInputStream(stream, chunkSize = 64 * 1024))
      }
      .mapError(storeError(StoreOperation.GetObject))

  protected final def target(locator: BlobLocator): Task[S3ObjectTarget] =
    if locator.scheme.value != config.scheme then
      ZIO.fail(new IllegalArgumentException(s"Expected '${config.scheme}' locator, received '${locator.scheme.value}'"))
    else if locator.bucket.value != config.storage.bucket then
      ZIO.fail(new IllegalArgumentException(s"Expected bucket '${config.storage.bucket}', received '${locator.bucket.value}'"))
    else ZIO.succeed(S3ObjectTarget(locator.bucket.value, prefixed(locator.path.value)))

  protected final def storeError(operation: StoreOperation)(error: Throwable): StoreError =
    S3StoreError.fromThrowable(operation)(error)

  private def prefixed(path: String): String =
    val root     = config.storage.prefix.trim.stripPrefix("/").stripSuffix("/")
    val relative = path.trim.stripPrefix("/")
    if root.isEmpty then relative
    else if relative.isEmpty then s"$root/"
    else s"$root/$relative"

  private def relative(key: String): String =
    val root = config.storage.prefix.trim.stripPrefix("/").stripSuffix("/")
    if root.isEmpty then key
    else key.stripPrefix(s"$root/")

private[s3] final case class S3ObjectTarget(bucket: String, key: String)
