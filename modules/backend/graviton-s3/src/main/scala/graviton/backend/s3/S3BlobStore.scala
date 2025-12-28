package graviton.backend.s3

import graviton.core.attributes.BlobWriteResult
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.locator.BlobLocator
import graviton.core.types.{ChunkCount, FileSize, MaxBlockBytes}
import graviton.runtime.model.{BlobStat, BlobWritePlan}
import graviton.runtime.stores.BlobStore
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import zio.stream.{ZSink, ZStream}
import zio.{Chunk, IO, Task, ZIO, ZLayer}

import java.io.ByteArrayOutputStream
import java.util.UUID
import scala.jdk.CollectionConverters.*

final case class S3BlobStoreConfig(
  blobs: S3Config,
  tmp: S3Config,
  partSizeBytes: Int = 5 * 1024 * 1024, // S3 multipart min part size (except last)
  scheme: String = "s3",
)

final class S3BlobStore(
  client: S3Client,
  config: S3BlobStoreConfig,
) extends BlobStore:

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink.unwrapScoped {
      for
        hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
        state   = PutState.initial(hasher, config)
      yield ZSink
        .foldLeftChunksZIO[Any, Throwable, Byte, PutState](state) { (s, chunk) =>
          s.ingest(chunk, client)
        }
        .mapZIO(_.finish(client, plan))
        .ignoreLeftover
    }

  override def get(key: BinaryKey): ZStream[Any, Throwable, Byte] =
    key match
      case blob: BinaryKey.Blob =>
        val req =
          GetObjectRequest
            .builder()
            .bucket(config.blobs.bucket)
            .key(objectKeyFor(blob))
            .build()

        ZStream
          .acquireReleaseWith(
            ZIO.attemptBlocking(client.getObject(req))
          )(is => ZIO.attemptBlocking(is.close()).orDie)
          .flatMap { is =>
            ZStream.fromInputStream(is, chunkSize = 64 * 1024)
          }

      case other =>
        ZStream.fail(new UnsupportedOperationException(s"S3BlobStore.get only supports blob keys, got $other"))

  override def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]] =
    // Not implemented: would require either storing digest metadata or re-hashing the object.
    ZIO.succeed(None)

  override def delete(key: BinaryKey): ZIO[Any, Throwable, Unit] =
    key match
      case blob: BinaryKey.Blob =>
        val req =
          DeleteObjectRequest
            .builder()
            .bucket(config.blobs.bucket)
            .key(objectKeyFor(blob))
            .build()
        ZIO.attemptBlocking(client.deleteObject(req)).unit
      case other                =>
        ZIO.fail(new UnsupportedOperationException(s"S3BlobStore.delete only supports blob keys, got $other"))

  private def objectKeyFor(key: BinaryKey.Blob): String =
    val base   = key.bits.digest.hex.value
    val prefix = config.blobs.prefix.trim
    if prefix.isEmpty then base
    else s"${prefix.stripSuffix("/")}/$base"

object S3BlobStore:

  /**
   * Minimal env contract for the on-prem v1 compose bundle:
   *
   * Required:
   * - QUASAR_MINIO_URL
   * - MINIO_ROOT_USER
   * - MINIO_ROOT_PASSWORD
   *
   * Optional:
   * - GRAVITON_S3_BUCKET (defaults to graviton-blobs)
   * - GRAVITON_S3_TMP_BUCKET (defaults to graviton-tmp)
   * - GRAVITON_S3_REGION (defaults to us-east-1)
   */
  val layerFromEnv: ZLayer[Any, Throwable, BlobStore] =
    ZLayer.fromZIO {
      val blobBucket = sys.env.get("GRAVITON_S3_BUCKET").filter(_.nonEmpty).getOrElse("graviton-blobs")
      val tmpBucket  = sys.env.get("GRAVITON_S3_TMP_BUCKET").filter(_.nonEmpty).getOrElse("graviton-tmp")

      for
        base   <- ZIO
                    .fromEither(S3Config.fromEndpointEnv(bucket = blobBucket))
                    .mapError(msg => new IllegalArgumentException(msg))
        tmp    <- ZIO
                    .fromEither(S3Config.fromEndpointEnv(bucket = tmpBucket))
                    .mapError(msg => new IllegalArgumentException(msg))
        client <- S3ClientLayer.make(base)
      yield new S3BlobStore(client, S3BlobStoreConfig(blobs = base, tmp = tmp))
    }

private final case class PutState(
  hasher: Hasher,
  totalBytes: Long,
  buffer: ByteArrayOutputStream,
  multipart: Option[MultipartState],
  config: S3BlobStoreConfig,
):

  def ingest(chunk: Chunk[Byte], client: S3Client): Task[PutState] =
    ZIO
      .attempt {
        val arr = chunk.toArray
        val _   = hasher.update(arr)

        buffer.write(arr)

        this.copy(totalBytes = totalBytes + arr.length.toLong)
      }
      .flatMap(_.flushFullParts(client))

  private def flushFullParts(client: S3Client): Task[PutState] =
    val partSize = config.partSizeBytes

    def ensureMultipart(state: PutState): Task[PutState] =
      state.multipart match
        case Some(_) => ZIO.succeed(state)
        case None    =>
          ZIO.attemptBlocking {
            val uploadKey = state.tempObjectKey
            val req       =
              CreateMultipartUploadRequest
                .builder()
                .bucket(state.config.tmp.bucket)
                .key(uploadKey)
                .build()
            val resp      = client.createMultipartUpload(req)
            state.copy(multipart =
              Some(
                MultipartState(
                  uploadId = resp.uploadId(),
                  key = uploadKey,
                  nextPartNumber = 1,
                  parts = Nil,
                )
              )
            )
          }

    def uploadOneFullPart(state: PutState): Task[PutState] =
      ensureMultipart(state).flatMap { ensured =>
        ZIO.attemptBlocking {
          val mp        = ensured.multipart.get
          val bytes     = ensured.buffer.toByteArray
          val partBytes = java.util.Arrays.copyOfRange(bytes, 0, partSize)
          val remainder = java.util.Arrays.copyOfRange(bytes, partSize, bytes.length)

          val req =
            UploadPartRequest
              .builder()
              .bucket(ensured.config.tmp.bucket)
              .key(mp.key)
              .uploadId(mp.uploadId)
              .partNumber(mp.nextPartNumber)
              .contentLength(partBytes.length.toLong)
              .build()

          val resp = client.uploadPart(req, RequestBody.fromBytes(partBytes))

          val completed = CompletedPart.builder().partNumber(mp.nextPartNumber).eTag(resp.eTag()).build()
          val nextMp    =
            mp.copy(
              nextPartNumber = mp.nextPartNumber + 1,
              parts = mp.parts :+ completed,
            )

          val nextBuf = new ByteArrayOutputStream(math.max(remainder.length, 32))
          nextBuf.write(remainder)
          ensured.copy(buffer = nextBuf, multipart = Some(nextMp))
        }
      }

    def loop(state: PutState): Task[PutState] =
      if state.buffer.size() < partSize then ZIO.succeed(state)
      else uploadOneFullPart(state).flatMap(loop)

    loop(this)

  def finish(client: S3Client, plan: BlobWritePlan): IO[Throwable, BlobWriteResult] =
    for
      _       <- ZIO.fail(new IllegalArgumentException("Empty blobs are not supported (size must be > 0)")).when(totalBytes <= 0L)
      digest  <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
      bits    <- ZIO.fromEither(KeyBits.create(hasher.algo, digest, totalBytes)).mapError(msg => new IllegalArgumentException(msg))
      key     <- ZIO.fromEither(BinaryKey.blob(bits)).mapError(msg => new IllegalArgumentException(msg))
      size    <- ZIO.fromEither(FileSize.either(totalBytes)).mapError(msg => new IllegalArgumentException(msg))
      count   <- ZIO.fromEither(ChunkCount.either(deriveChunkCount(totalBytes))).mapError(msg => new IllegalArgumentException(msg))
      attrs    = plan.attributes.confirmSize(size).confirmChunkCount(count)
      _       <- multipart match
                   case None     =>
                     // Small object: upload directly to final key (buffer is bounded by partSize).
                     val req =
                       PutObjectRequest
                         .builder()
                         .bucket(config.blobs.bucket)
                         .key(finalObjectKeyFor(key))
                         .build()
                     ZIO.attemptBlocking(client.putObject(req, RequestBody.fromBytes(buffer.toByteArray))).unit
                   case Some(mp) =>
                     // Upload last part (may be < 5MiB), complete multipart, then copy into final CAS key.
                     val lastBytes = buffer.toByteArray
                     for
                       _ <-
                         ZIO.attemptBlocking {
                           val req  =
                             UploadPartRequest
                               .builder()
                               .bucket(config.tmp.bucket)
                               .key(mp.key)
                               .uploadId(mp.uploadId)
                               .partNumber(mp.nextPartNumber)
                               .contentLength(lastBytes.length.toLong)
                               .build()
                           val resp = client.uploadPart(req, RequestBody.fromBytes(lastBytes))
                           val last = CompletedPart.builder().partNumber(mp.nextPartNumber).eTag(resp.eTag()).build()
                           val all  = (mp.parts :+ last).asJava

                           val completed =
                             CompletedMultipartUpload
                               .builder()
                               .parts(all)
                               .build()

                           client.completeMultipartUpload(
                             CompleteMultipartUploadRequest
                               .builder()
                               .bucket(config.tmp.bucket)
                               .key(mp.key)
                               .uploadId(mp.uploadId)
                               .multipartUpload(completed)
                               .build()
                           )
                         }.unit
                       _ <- ZIO.attemptBlocking {
                              val copyReq =
                                CopyObjectRequest
                                  .builder()
                                  .sourceBucket(config.tmp.bucket)
                                  .sourceKey(mp.key)
                                  .destinationBucket(config.blobs.bucket)
                                  .destinationKey(finalObjectKeyFor(key))
                                  .build()
                              client.copyObject(copyReq)
                            }.unit
                       _ <- ZIO.attemptBlocking {
                              client.deleteObject(DeleteObjectRequest.builder().bucket(config.tmp.bucket).key(mp.key).build())
                            }.unit
                     yield ()
      locator <-
        plan.locatorHint match
          case Some(value) => ZIO.succeed(value)
          case None        =>
            ZIO
              .fromEither(BlobLocator.from(config.scheme, config.blobs.bucket, finalObjectKeyFor(key)))
              .mapError(msg => new IllegalArgumentException(msg))
    yield BlobWriteResult(key, locator, attrs)

  private def deriveChunkCount(length: Long): Long =
    if length <= 0 then 0L
    else ((length - 1) / MaxBlockBytes + 1)

  private def tempObjectKey: String =
    val prefix = config.tmp.prefix.trim
    val name   = s"tmp/${UUID.randomUUID().toString}"
    if prefix.isEmpty then name else s"${prefix.stripSuffix("/")}/$name"

  private def finalObjectKeyFor(key: BinaryKey.Blob): String =
    val base   = key.bits.digest.hex.value
    val prefix = config.blobs.prefix.trim
    if prefix.isEmpty then base else s"${prefix.stripSuffix("/")}/$base"

object PutState:
  def initial(hasher: Hasher, config: S3BlobStoreConfig): PutState =
    PutState(hasher, totalBytes = 0L, buffer = new ByteArrayOutputStream(64 * 1024), multipart = None, config = config)

private final case class MultipartState(
  uploadId: String,
  key: String,
  nextPartNumber: Int,
  parts: List[CompletedPart],
)
