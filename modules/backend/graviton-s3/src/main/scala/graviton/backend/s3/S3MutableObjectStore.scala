package graviton.backend.s3

import graviton.core.locator.BlobLocator
import graviton.runtime.stores.{MutableObjectStore, StoreError, StoreOperation, TransferBudget}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import zio.*
import zio.stream.*

import scala.jdk.CollectionConverters.*

final class S3MutableObjectStore(
  client: S3Client,
  config: S3ObjectStoreConfig,
  transferBudget: TransferBudget,
) extends S3ImmutableObjectStore(client, config),
      MutableObjectStore:

  override def put(locator: BlobLocator): ZSink[Any, StoreError, Byte, Nothing, Unit] =
    ZSink.unwrapScoped {
      (for
        _            <- transferBudget.reserveScoped(S3BlobStore.MaxBufferedPartBytes.toLong)
        objectTarget <- target(locator)
        active       <- Ref.make(Option.empty[ActiveMultipartUpload])
        _            <- ZIO.addFinalizer(abortActive(active))
      yield ZSink
        .foldLeftChunksZIO[Any, Throwable, Byte, ObjectUploadState](
          ObjectUploadState.initial(objectTarget, config.partSizeBytes, active)
        )((state, chunk) => state.ingest(client, chunk))
        .mapZIO(_.finish(client))
        .ignoreLeftover
        .mapError(storeError(StoreOperation.PutObject))).mapError(storeError(StoreOperation.PutObject))
    }

  override def delete(locator: BlobLocator): IO[StoreError, Unit] =
    target(locator)
      .flatMap { objectTarget =>
        ZIO
          .attemptBlocking(
            client.deleteObject(DeleteObjectRequest.builder().bucket(objectTarget.bucket).key(objectTarget.key).build())
          )
          .unit
      }
      .mapError(storeError(StoreOperation.DeleteObject))

  override def copy(src: BlobLocator, dest: BlobLocator): IO[StoreError, Unit] =
    (for
      source      <- target(src)
      destination <- target(dest)
      size        <- head(src).someOrFail(StoreError.ObjectNotFound(StoreOperation.CopyObject, src))
      _           <- S3BlobStore.promoteTempObject(
                       client = client,
                       sourceBucket = source.bucket,
                       sourceKey = source.key,
                       destinationBucket = destination.bucket,
                       destinationKey = destination.key,
                       size = size,
                     )
    yield ()).mapError(storeError(StoreOperation.CopyObject))

  private def abortActive(ref: Ref[Option[ActiveMultipartUpload]]): UIO[Unit] =
    ref.get.flatMap {
      case None         => ZIO.unit
      case Some(active) =>
        ZIO
          .attemptBlocking(
            client.abortMultipartUpload(
              AbortMultipartUploadRequest
                .builder()
                .bucket(active.bucket)
                .key(active.key)
                .uploadId(active.uploadId)
                .build()
            )
          )
          .ignore
    }

private final case class ActiveMultipartUpload(bucket: String, key: String, uploadId: String)

private final case class ObjectUploadState(
  target: S3ObjectTarget,
  partSizeBytes: S3BlobStore.PartSize,
  buffer: S3BlobStore.PartBuffer,
  multipart: Option[ActiveMultipartUpload],
  completedParts: Vector[CompletedPart],
  nextPartNumber: Int,
  active: Ref[Option[ActiveMultipartUpload]],
):

  private def targetPartSizeBytes: Int =
    S3BlobStore.partSizeForNumber(partSizeBytes, nextPartNumber)

  def ingest(client: S3Client, input: Chunk[Byte]): Task[ObjectUploadState] =
    def loop(state: ObjectUploadState, remaining: Chunk[Byte]): Task[ObjectUploadState] =
      if remaining.isEmpty then ZIO.succeed(state)
      else
        val capacity = state.targetPartSizeBytes - state.buffer.length
        val accepted = remaining.take(capacity)
        val rest     = remaining.drop(capacity)
        for
          nextBuffer <- ZIO
                          .fromEither(S3BlobStore.PartBuffer.fromChunk(state.buffer ++ accepted))
                          .mapError(message => new IllegalArgumentException(message))
          next        = state.copy(buffer = nextBuffer)
          flushed    <- if next.buffer.length == next.targetPartSizeBytes then next.flush(client) else ZIO.succeed(next)
          result     <- loop(flushed, rest)
        yield result

    loop(this, input)

  private def flush(client: S3Client): Task[ObjectUploadState] =
    for
      ready <- ensureMultipart(client)
      _     <- ZIO
                 .fail(new IllegalArgumentException(s"S3 multipart upload exceeds ${S3BlobStore.MaxMultipartParts} parts"))
                 .when(ready.nextPartNumber > S3BlobStore.MaxMultipartParts)
      part  <- ZIO.attemptBlocking {
                 val current  = ready.multipart.get
                 val bytes    = ready.buffer.toArray
                 val request  = UploadPartRequest
                   .builder()
                   .bucket(current.bucket)
                   .key(current.key)
                   .uploadId(current.uploadId)
                   .partNumber(ready.nextPartNumber)
                   .contentLength(bytes.length.toLong)
                   .build()
                 val response = client.uploadPart(request, RequestBody.fromBytes(bytes))
                 CompletedPart.builder().partNumber(ready.nextPartNumber).eTag(response.eTag()).build()
               }
    yield ready.copy(
      buffer = S3BlobStore.PartBuffer.empty,
      completedParts = ready.completedParts :+ part,
      nextPartNumber = ready.nextPartNumber + 1,
    )

  private def ensureMultipart(client: S3Client): Task[ObjectUploadState] =
    multipart match
      case Some(_) => ZIO.succeed(this)
      case None    =>
        for
          created <- ZIO.attemptBlocking {
                       val response = client.createMultipartUpload(
                         CreateMultipartUploadRequest
                           .builder()
                           .bucket(target.bucket)
                           .key(target.key)
                           .build()
                       )
                       ActiveMultipartUpload(target.bucket, target.key, response.uploadId())
                     }
          _       <- active.set(Some(created))
        yield copy(multipart = Some(created))

  def finish(client: S3Client): Task[Unit] =
    multipart match
      case None          =>
        ZIO
          .attemptBlocking(
            client.putObject(
              PutObjectRequest
                .builder()
                .bucket(target.bucket)
                .key(target.key)
                .contentLength(buffer.length.toLong)
                .build(),
              RequestBody.fromBytes(buffer.toArray),
            )
          )
          .unit
      case Some(current) =>
        for
          finalState <- if buffer.isEmpty then ZIO.succeed(this) else flush(client)
          _          <- ZIO.attemptBlocking {
                          client.completeMultipartUpload(
                            CompleteMultipartUploadRequest
                              .builder()
                              .bucket(current.bucket)
                              .key(current.key)
                              .uploadId(current.uploadId)
                              .multipartUpload(
                                CompletedMultipartUpload.builder().parts(finalState.completedParts.asJava).build()
                              )
                              .build()
                          )
                        }
          _          <- active.set(None)
        yield ()

private object ObjectUploadState:
  def initial(
    target: S3ObjectTarget,
    partSizeBytes: S3BlobStore.PartSize,
    active: Ref[Option[ActiveMultipartUpload]],
  ): ObjectUploadState =
    ObjectUploadState(
      target = target,
      partSizeBytes = partSizeBytes,
      buffer = S3BlobStore.PartBuffer.empty,
      multipart = None,
      completedParts = Vector.empty,
      nextPartNumber = 1,
      active = active,
    )
