package graviton.backend.s3

import graviton.core.attributes.BlobWriteResult
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.locator.BlobLocator
import graviton.core.types.{ChunkCount, FileSize, MaxBlockBytes}
import graviton.runtime.model.{BlobStat, BlobWritePlan}
import graviton.runtime.stores.BlobStore
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import zio.stream.{ZSink, ZStream}
import zio.{Chunk, IO, Task, ZIO, ZLayer}

import java.util.UUID
import scala.jdk.CollectionConverters.*

final case class S3BlobStoreConfig(
  blobs: S3Config,
  tmp: S3Config,
  partSizeBytes: S3BlobStore.PartSize = S3BlobStore.PartSize.Default,
  scheme: String = "s3",
)

final class S3BlobStore(
  client: S3Client,
  config: S3BlobStoreConfig,
) extends BlobStore:

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink.unwrapScoped {
      for
        _      <-
          ZIO
            .fromEither(plan.attributes.validate)
            .mapError(msg => new IllegalArgumentException(s"Invalid binary attributes in BlobWritePlan: $msg"))
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

  val MaxMultipartParts: Int    = 10000
  val PartGrowthWindow: Int     = 256
  val MaxBufferedPartBytes: Int = 128 * 1024 * 1024
  val OneTebibyte: Long         = 1024L * 1024L * 1024L * 1024L

  type PartSize = PartSize.T
  object PartSize extends RefinedSubtype[Int, GreaterEqual[5242880] & LessEqual[134217728]]:
    val Default: PartSize = applyUnsafe(5 * 1024 * 1024)

  type PartBuffer = Chunk[Byte] :| MaxLength[134217728]

  object PartBuffer:
    val empty: PartBuffer = Chunk.empty[Byte].refineUnsafe[MaxLength[134217728]]

    def fromChunk(bytes: Chunk[Byte]): Either[String, PartBuffer] =
      bytes.refineEither[MaxLength[134217728]]

  /**
   * Grow parts for an unknown-length stream while retaining a hard memory
   * ceiling. Small uploads begin at the configured size; every 256 parts the
   * target doubles until it reaches 128 MiB.
   */
  private[s3] def partSizeForNumber(base: PartSize, partNumber: Int): Int =
    val window     = math.max(0, (partNumber - 1) / PartGrowthWindow)
    val multiplier = 1L << math.min(window, 30)
    math.min(MaxBufferedPartBytes.toLong, base.value.toLong * multiplier).toInt

  private[s3] def multipartCapacityBytes(base: PartSize): Long =
    (1 to MaxMultipartParts).foldLeft(0L) { (total, partNumber) =>
      total + partSizeForNumber(base, partNumber).toLong
    }

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
      for
        blobBucket <- ZIO.succeed(sys.env.get("GRAVITON_S3_BUCKET").filter(_.nonEmpty).getOrElse("graviton-blobs"))
        tmpBucket  <- ZIO.succeed(sys.env.get("GRAVITON_S3_TMP_BUCKET").filter(_.nonEmpty).getOrElse("graviton-tmp"))
        base       <- ZIO
                        .fromEither(S3Config.fromEnvironment(bucket = blobBucket))
                        .mapError(msg => new IllegalArgumentException(msg))
        tmp        <- ZIO
                        .fromEither(S3Config.fromEnvironment(bucket = tmpBucket))
                        .mapError(msg => new IllegalArgumentException(msg))
        client     <- S3ClientLayer.make(base)
      yield new S3BlobStore(client, S3BlobStoreConfig(blobs = base, tmp = tmp))
    }

private final case class PutState(
  hasher: Hasher,
  totalBytes: Long,
  buffer: S3BlobStore.PartBuffer,
  multipart: Option[MultipartState],
  config: S3BlobStoreConfig,
):

  private def targetPartSizeBytes: Int =
    S3BlobStore.partSizeForNumber(config.partSizeBytes, multipart.fold(1)(_.nextPartNumber))

  def ingest(chunk: Chunk[Byte], client: S3Client): Task[PutState] =
    def loop(state: PutState, remaining: Chunk[Byte]): Task[PutState] =
      if remaining.isEmpty then ZIO.succeed(state)
      else
        val capacity = state.targetPartSizeBytes - state.buffer.length
        val accepted = remaining.take(capacity)
        val rest     = remaining.drop(capacity)
        for
          _          <- ZIO.attempt(state.hasher.update(accepted))
          nextBuffer <- ZIO
                          .fromEither(S3BlobStore.PartBuffer.fromChunk(state.buffer ++ accepted))
                          .mapError(new IllegalArgumentException(_))
          next        = state.copy(
                          totalBytes = state.totalBytes + accepted.length.toLong,
                          buffer = nextBuffer,
                        )
          flushed    <- if next.buffer.length == next.targetPartSizeBytes then next.uploadFullPart(client)
                        else ZIO.succeed(next)
          result     <- loop(flushed, rest)
        yield result

    loop(this, chunk)

  private def uploadFullPart(client: S3Client): Task[PutState] =

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

    ensureMultipart(this).flatMap { ensured =>
      ZIO
        .fail(new IllegalArgumentException(s"S3 multipart upload exceeds ${S3BlobStore.MaxMultipartParts} parts"))
        .when(ensured.multipart.exists(_.nextPartNumber > S3BlobStore.MaxMultipartParts)) *>
        ZIO.attemptBlocking {
          val mp        = ensured.multipart.get
          val partBytes = ensured.buffer.toArray

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

          ensured.copy(buffer = S3BlobStore.PartBuffer.empty, multipart = Some(nextMp))
        }
    }

  def finish(client: S3Client, plan: BlobWritePlan): IO[Throwable, BlobWriteResult] =
    for
      _              <- ZIO.fail(new IllegalArgumentException("Empty blobs are not supported (size must be > 0)")).when(totalBytes <= 0L)
      digest         <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
      bits           <- ZIO.fromEither(KeyBits.create(hasher.algo, digest, totalBytes)).mapError(msg => new IllegalArgumentException(msg))
      key            <- ZIO.fromEither(BinaryKey.blob(bits)).mapError(msg => new IllegalArgumentException(msg))
      size           <- ZIO.fromEither(FileSize.either(totalBytes)).mapError(msg => new IllegalArgumentException(msg))
      count          <- ZIO.fromEither(ChunkCount.either(deriveChunkCount(totalBytes))).mapError(msg => new IllegalArgumentException(msg))
      attrs           = plan.attributes.confirmSize(size).confirmChunkCount(count)
      validatedAttrs <- ZIO
                          .fromEither(attrs.validate)
                          .mapError(msg => new IllegalStateException(s"Generated invalid confirmed attributes: $msg"))
      _              <- multipart match
                          case None     =>
                            // Small object: upload directly to final key (buffer is bounded by partSize).
                            val req =
                              PutObjectRequest
                                .builder()
                                .bucket(config.blobs.bucket)
                                .key(finalObjectKeyFor(key))
                                .build()
                            ZIO.attemptBlocking(client.putObject(req, RequestBody.fromBytes(buffer.toArray))).unit
                          case Some(mp) =>
                            // Upload a non-empty last part, complete multipart, then copy into the final CAS key.
                            for
                              allParts <-
                                if buffer.isEmpty then ZIO.succeed(mp.parts)
                                else if mp.nextPartNumber > S3BlobStore.MaxMultipartParts then
                                  ZIO.fail(
                                    new IllegalArgumentException(
                                      s"S3 multipart upload exceeds ${S3BlobStore.MaxMultipartParts} parts"
                                    )
                                  )
                                else
                                  ZIO.attemptBlocking {
                                    val lastBytes = buffer.toArray
                                    val req       =
                                      UploadPartRequest
                                        .builder()
                                        .bucket(config.tmp.bucket)
                                        .key(mp.key)
                                        .uploadId(mp.uploadId)
                                        .partNumber(mp.nextPartNumber)
                                        .contentLength(lastBytes.length.toLong)
                                        .build()
                                    val resp      = client.uploadPart(req, RequestBody.fromBytes(lastBytes))
                                    val last      = CompletedPart.builder().partNumber(mp.nextPartNumber).eTag(resp.eTag()).build()
                                    mp.parts :+ last
                                  }
                              _        <- ZIO.attemptBlocking {
                                            val completed =
                                              CompletedMultipartUpload
                                                .builder()
                                                .parts(allParts.asJava)
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
                              _        <- ZIO.attemptBlocking {
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
                              _        <- ZIO.attemptBlocking {
                                            client.deleteObject(DeleteObjectRequest.builder().bucket(config.tmp.bucket).key(mp.key).build())
                                          }.unit
                            yield ()
      locator        <-
        plan.locatorHint match
          case Some(value) => ZIO.succeed(value)
          case None        =>
            ZIO
              .fromEither(BlobLocator.from(config.scheme, config.blobs.bucket, finalObjectKeyFor(key)))
              .mapError(msg => new IllegalArgumentException(msg))
    yield BlobWriteResult(key, locator, validatedAttrs)

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
    PutState(hasher, totalBytes = 0L, buffer = S3BlobStore.PartBuffer.empty, multipart = None, config = config)

private final case class MultipartState(
  uploadId: String,
  key: String,
  nextPartNumber: Int,
  parts: List[CompletedPart],
)
