package graviton.backend.s3

import graviton.core.attributes.BlobWriteResult
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.locator.BlobLocator
import graviton.core.types.{ChunkCount, FileSize, MaxBlockBytes}
import graviton.runtime.model.{
  BlobBlockDescription,
  BlobDescription,
  BlobListing,
  BlobStat,
  BlobWritePlan,
  InventoryCursor,
  InventoryNamespace,
  InventoryPage,
  InventoryPageSize,
}
import graviton.runtime.config.TransferMemoryConfig
import graviton.runtime.stores.{
  BackendInitError,
  BlobMetadataV1,
  BlobStore,
  ManifestChunkerId,
  StoreBackend,
  StoreError,
  StoreOperation,
  TransferBudget,
}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import zio.stream.{ZSink, ZStream}
import zio.{Chunk, IO, Task, ZIO, ZLayer}

import java.util.UUID
import java.util.Base64
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
  transferBudget: TransferBudget,
) extends BlobStore:

  override def put(plan: BlobWritePlan = BlobWritePlan()): BlobSink =
    ZSink.unwrapScoped {
      (for
        _      <- transferBudget.reserveScoped(S3BlobStore.MaxBufferedPartBytes.toLong)
        _      <-
          ZIO
            .fromEither(plan.attributes.validate)
            .mapError(msg => new IllegalArgumentException(s"Invalid binary attributes in BlobWritePlan: $msg"))
        hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
        temp   <- zio.Ref.make(Option.empty[TempResource])
        _      <- ZIO.addFinalizer(cleanupTemp(temp))
        state   = PutState.initial(hasher, config, temp)
      yield ZSink
        .foldLeftChunksZIO[Any, Throwable, Byte, PutState](state) { (s, chunk) =>
          s.ingest(chunk, client)
        }
        .mapZIO(_.finish(client, plan))
        .mapError(storeError(StoreOperation.PutBlob))).mapError(storeError(StoreOperation.PutBlob))
    }

  private def cleanupTemp(ref: zio.Ref[Option[TempResource]]): zio.UIO[Unit] =
    ref.get.flatMap {
      case None                                    => ZIO.unit
      case Some(TempResource(key, Some(uploadId))) =>
        ZIO
          .attemptBlocking(
            client.abortMultipartUpload(
              AbortMultipartUploadRequest
                .builder()
                .bucket(config.tmp.bucket)
                .key(key)
                .uploadId(uploadId)
                .build()
            )
          )
          .ignore
      case Some(TempResource(key, None))           =>
        ZIO
          .attemptBlocking(client.deleteObject(DeleteObjectRequest.builder().bucket(config.tmp.bucket).key(key).build()))
          .ignore
    }

  override def get(key: BinaryKey.Blob): ZStream[Any, StoreError, Byte] =
    val req =
      GetObjectRequest
        .builder()
        .bucket(config.blobs.bucket)
        .key(objectKeyFor(key))
        .build()

    ZStream
      .acquireReleaseWith(
        ZIO.attemptBlocking(client.getObject(req))
      )(is => ZIO.attemptBlocking(is.close()).orDie)
      .flatMap(is => ZStream.fromInputStream(is, chunkSize = 64 * 1024))
      .mapError(storeError(StoreOperation.GetBlob, Some(key)))

  override def stat(key: BinaryKey.Blob): IO[StoreError, Option[BlobStat]] =
    val request = HeadObjectRequest.builder().bucket(config.blobs.bucket).key(objectKeyFor(key)).build()
    ZIO
      .attemptBlocking(client.headObject(request))
      .flatMap { response =>
        ZIO
          .fromEither(FileSize.either(response.contentLength()))
          .mapError(message => new IllegalStateException(message))
          .map(size => Some(BlobStat(size, key.bits.digest, response.lastModified())))
      }
      .catchSome { case error: S3Exception if S3BlobStore.isNotFound(error) => ZIO.succeed(None) }
      .mapError(storeError(StoreOperation.StatBlob, Some(key)))

  override def inventoryPage(
    after: Option[InventoryCursor],
    limit: InventoryPageSize,
  ): IO[StoreError, InventoryPage[BlobListing]] =
    for
      anchor   <- ZIO
                    .fromEither(
                      after.fold[Either[String, Option[String]]](Right(None))(cursor =>
                        InventoryCursor.decode(cursor, InventoryNamespace.S3).map(Some(_))
                      )
                    )
                    .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
      response <- ZIO
                    .attemptBlocking {
                      val builder = ListObjectsV2Request
                        .builder()
                        .bucket(config.blobs.bucket)
                        .prefix(activeListPrefix)
                        .maxKeys(limit.value)
                      anchor.foreach(builder.startAfter)
                      client.listObjectsV2(builder.build())
                    }
                    .mapError(storeError(StoreOperation.Inventory))
      rows     <- ZIO
                    .foreach(Chunk.fromIterable(response.contents().asScala).take(limit.value)) { entry =>
                      ZIO
                        .fromEither(parseListing(entry))
                        .mapError(StoreError.CorruptData(StoreOperation.Inventory, _))
                    }
      next     <- ZIO.foreach(rows.lastOption.filter(_ => response.isTruncated)) { listing =>
                    ZIO
                      .fromEither(InventoryCursor.encode(InventoryNamespace.S3, objectKeyFor(listing.key)))
                      .mapError(StoreError.InvalidInput(StoreOperation.Inventory, _))
                  }
    yield InventoryPage(rows, next)

  override def inspect(key: BinaryKey.Blob): IO[StoreError, Option[BlobDescription]] =
    stat(key).flatMap {
      case None        => ZIO.succeed(None)
      case Some(value) =>
        ZIO
          .fromEither(BinaryKey.block(key.bits))
          .mapError(StoreError.CorruptData(StoreOperation.InspectBlob, _))
          .map { blockKey =>
            val listing = BlobListing(key, value, blockCount = 1)
            Some(BlobDescription(listing, Chunk(BlobBlockDescription(0L, blockKey, 0L, value.size.value))))
          }
    }

  override def metadata(key: BinaryKey.Blob): IO[StoreError, Option[BlobMetadataV1]] =
    val request = HeadObjectRequest.builder().bucket(config.blobs.bucket).key(objectKeyFor(key)).build()
    ZIO
      .attemptBlocking(client.headObject(request))
      .flatMap(response =>
        ZIO
          .fromEither(S3BlobStore.decodeMetadata(response.metadata().asScala.toMap))
          .mapError(message => new IllegalArgumentException(message))
          .map(Some(_))
      )
      .catchSome { case error: S3Exception if S3BlobStore.isNotFound(error) => ZIO.succeed(None) }
      .mapError(storeError(StoreOperation.GetManifest, Some(key)))

  override def delete(key: BinaryKey.Blob): IO[StoreError, Unit] =
    val req =
      DeleteObjectRequest
        .builder()
        .bucket(config.blobs.bucket)
        .key(objectKeyFor(key))
        .build()
    ZIO.attemptBlocking(client.deleteObject(req)).unit.mapError(storeError(StoreOperation.DeleteBlob, Some(key)))

  override def healthCheck: IO[StoreError, Unit] =
    ZIO
      .attemptBlocking(client.headBucket(HeadBucketRequest.builder().bucket(config.blobs.bucket).build()))
      .unit
      .mapError(storeError(StoreOperation.HealthCheck))

  private def parseListing(entry: S3Object): Either[String, BlobListing] =
    for
      key  <- parseObjectKey(entry.key()).left.map(message => s"Invalid Graviton blob object '${entry.key()}': $message")
      _    <- Either.cond(
                entry.size() == key.bits.size,
                (),
                s"S3 object '${entry.key()}' has ${entry.size()} bytes but its content key declares ${key.bits.size}",
              )
      size <- FileSize.either(entry.size())
    yield BlobListing(key, BlobStat(size, key.bits.digest, entry.lastModified()), blockCount = 1)

  private def storeError(operation: StoreOperation, key: Option[BinaryKey] = None)(error: Throwable): StoreError =
    S3StoreError.fromThrowable(operation, key)(error)

  private def objectKeyFor(key: BinaryKey.Blob): String =
    val base   = s"${S3BlobStore.algoPathSegment(key.bits.algo)}/${key.bits.digest.hex.value}-${key.bits.size}"
    val prefix = config.blobs.prefix.trim
    if prefix.isEmpty then base
    else s"${prefix.stripSuffix("/")}/$base"

  private val activeListPrefix: String =
    val prefix = config.blobs.prefix.trim.stripSuffix("/")
    if prefix.isEmpty then "" else s"$prefix/"

  private def parseObjectKey(value: String): Either[String, BinaryKey.Blob] =
    val relative = value.stripPrefix(activeListPrefix)
    relative.split("/", 2).toList match
      case algorithm :: fileName :: Nil =>
        fileName.lastIndexOf('-') match
          case separator if separator > 0 && separator < fileName.length - 1 =>
            for
              bits <- KeyBits.fromString(s"$algorithm:${fileName.substring(0, separator)}:${fileName.substring(separator + 1)}")
              key  <- BinaryKey.blob(bits)
              _    <- Either.cond(objectKeyFor(key) == value, (), "object key is not canonical")
            yield key
          case _                                                             => Left("expected <algorithm>/<hex>-<size>")
      case _                            => Left("expected <algorithm>/<hex>-<size>")

object S3BlobStore:

  val MaxMultipartParts: Int           = 10000
  val PartGrowthWindow: Int            = 256
  val MaxBufferedPartBytes: Int        = 128 * 1024 * 1024
  val OneTebibyte: Long                = 1024L * 1024L * 1024L * 1024L
  val SingleCopyMaxBytes: Long         = 4L * 1024L * 1024L * 1024L
  val CopyPartBytes: Long              = 512L * 1024L * 1024L
  val MetadataKey: String              = "graviton-blob-metadata"
  val ObjectChunker: ManifestChunkerId = ManifestChunkerId.applyUnsafe("s3-object-v1")

  type PartSize = PartSize.T
  object PartSize extends RefinedSubtype[Int, GreaterEqual[5242880] & LessEqual[134217728]]:
    val Default: PartSize = applyUnsafe(5 * 1024 * 1024)

  type PartBuffer = Chunk[Byte] :| MaxLength[134217728]

  object PartBuffer:
    val empty: PartBuffer = Chunk.empty[Byte].refineUnsafe[MaxLength[134217728]]

    def fromChunk(bytes: Chunk[Byte]): Either[String, PartBuffer] =
      bytes.refineEither[MaxLength[134217728]]

  private[s3] def isNotFound(error: S3Exception): Boolean =
    error.statusCode() == 404 || Option(error.awsErrorDetails()).exists(details => details.errorCode() == "NoSuchKey")

  private[s3] def algoPathSegment(algo: graviton.core.bytes.HashAlgo): String =
    algo match
      case graviton.core.bytes.HashAlgo.Sha256 => "sha256"
      case graviton.core.bytes.HashAlgo.Blake3 => "blake3"
      case other                               => other.primaryName

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

  private[s3] final case class CopyRange(partNumber: Int, start: Long, endInclusive: Long)

  private[s3] def copyRanges(size: Long): Either[String, Chunk[CopyRange]] =
    if size <= 0L then Left("S3 multipart copy size must be positive")
    else
      val count = ((size - 1L) / CopyPartBytes) + 1L
      if count > MaxMultipartParts.toLong then Left(s"S3 multipart copy requires $count parts, exceeding $MaxMultipartParts")
      else
        Right(
          Chunk.fromIterable((1L to count).map { number =>
            val start = (number - 1L) * CopyPartBytes
            CopyRange(number.toInt, start, math.min(size - 1L, start + CopyPartBytes - 1L))
          })
        )

  private[s3] def promoteTempObject(
    client: S3Client,
    sourceBucket: String,
    sourceKey: String,
    destinationBucket: String,
    destinationKey: String,
    size: Long,
  ): Task[Unit] =
    promoteTempObject(
      client,
      sourceBucket,
      sourceKey,
      destinationBucket,
      destinationKey,
      size,
      BlobMetadataV1.DefaultMediaType,
      java.util.Map.of[String, String](),
    )

  private[s3] def promoteTempObject(
    client: S3Client,
    sourceBucket: String,
    sourceKey: String,
    destinationBucket: String,
    destinationKey: String,
    size: Long,
    contentType: String,
    metadata: java.util.Map[String, String],
  ): Task[Unit] =
    if size <= SingleCopyMaxBytes then
      ZIO.attemptBlocking {
        client.copyObject(
          CopyObjectRequest
            .builder()
            .sourceBucket(sourceBucket)
            .sourceKey(sourceKey)
            .destinationBucket(destinationBucket)
            .destinationKey(destinationKey)
            .metadataDirective(MetadataDirective.REPLACE)
            .contentType(contentType)
            .metadata(metadata)
            .build()
        )
      }.unit
    else
      for
        ranges  <- ZIO.fromEither(copyRanges(size)).mapError(message => new IllegalArgumentException(message))
        created <- ZIO.attemptBlocking {
                     client.createMultipartUpload(
                       CreateMultipartUploadRequest
                         .builder()
                         .bucket(destinationBucket)
                         .key(destinationKey)
                         .contentType(contentType)
                         .metadata(metadata)
                         .build()
                     )
                   }
        uploadId = created.uploadId()
        _       <- (for
                     parts <- ZIO.foreach(ranges) { range =>
                                ZIO.attemptBlocking {
                                  val response = client.uploadPartCopy(
                                    UploadPartCopyRequest
                                      .builder()
                                      .sourceBucket(sourceBucket)
                                      .sourceKey(sourceKey)
                                      .destinationBucket(destinationBucket)
                                      .destinationKey(destinationKey)
                                      .copySourceRange(s"bytes=${range.start}-${range.endInclusive}")
                                      .uploadId(uploadId)
                                      .partNumber(range.partNumber)
                                      .build()
                                  )
                                  val result   = Option(response.copyPartResult()).getOrElse(
                                    throw new IllegalStateException(s"S3 copy part ${range.partNumber} returned no result")
                                  )
                                  CompletedPart
                                    .builder()
                                    .partNumber(range.partNumber)
                                    .eTag(Option(result.eTag()).getOrElse(throw new IllegalStateException("S3 copy part returned no ETag")))
                                    .build()
                                }
                              }
                     _     <- ZIO.attemptBlocking {
                                client.completeMultipartUpload(
                                  CompleteMultipartUploadRequest
                                    .builder()
                                    .bucket(destinationBucket)
                                    .key(destinationKey)
                                    .uploadId(uploadId)
                                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts.asJava).build())
                                    .build()
                                )
                              }
                   yield ()).onExit {
                     case zio.Exit.Success(_) => ZIO.unit
                     case _                   =>
                       ZIO
                         .attemptBlocking(
                           client.abortMultipartUpload(
                             AbortMultipartUploadRequest
                               .builder()
                               .bucket(destinationBucket)
                               .key(destinationKey)
                               .uploadId(uploadId)
                               .build()
                           )
                         )
                         .ignore
                   }
      yield ()

  private[s3] def encodeMetadata(value: BlobMetadataV1): Either[String, java.util.Map[String, String]] =
    BlobMetadataV1.encode(value).map { bytes =>
      Map(MetadataKey -> Base64.getUrlEncoder.withoutPadding().encodeToString(bytes.toArray)).asJava
    }

  private[s3] def decodeMetadata(values: Map[String, String]): Either[String, BlobMetadataV1] =
    values.get(MetadataKey).toRight(s"S3 object is missing '$MetadataKey' metadata").flatMap { encoded =>
      scala.util
        .Try(Chunk.fromArray(Base64.getUrlDecoder.decode(encoded)))
        .toEither
        .left
        .map(_ => s"S3 object '$MetadataKey' is not valid base64url")
        .flatMap(BlobMetadataV1.decode)
    }

  /**
   * Explicit S3-compatible endpoint contract:
   *
   * Required:
   * - GRAVITON_S3_ENDPOINT
   * - GRAVITON_S3_ACCESS_KEY
   * - GRAVITON_S3_SECRET_KEY
   *
   * Optional:
   * - GRAVITON_S3_BUCKET (defaults to graviton-blobs)
   * - GRAVITON_S3_TMP_BUCKET (defaults to graviton-tmp)
   * - GRAVITON_S3_REGION (defaults to us-east-1)
   */
  val layerFromEnvTyped: ZLayer[Any, BackendInitError, BlobStore] =
    ZLayer.scoped {
      for
        blobBucket <- ZIO.succeed(sys.env.get("GRAVITON_S3_BUCKET").filter(_.nonEmpty).getOrElse("graviton-blobs"))
        tmpBucket  <- ZIO.succeed(sys.env.get("GRAVITON_S3_TMP_BUCKET").filter(_.nonEmpty).getOrElse("graviton-tmp"))
        base       <- ZIO
                        .fromEither(S3Config.fromEnvironment(bucket = blobBucket))
                        .mapError(BackendInitError.InvalidConfiguration(StoreBackend.S3, _))
        tmp        <- ZIO
                        .fromEither(S3Config.fromEnvironment(bucket = tmpBucket))
                        .mapError(BackendInitError.InvalidConfiguration(StoreBackend.S3, _))
        client     <- S3ClientLayer.scoped(base)
        budget     <- TransferBudget.make(TransferMemoryConfig.Default)
      yield new S3BlobStore(client, S3BlobStoreConfig(blobs = base, tmp = tmp), budget)
    }

  @deprecated("Use layerFromEnvTyped to preserve the backend initialization error ADT", "0.9.0")
  val layerFromEnv: ZLayer[Any, Throwable, BlobStore] = layerFromEnvTyped

private final case class PutState(
  hasher: Hasher,
  totalBytes: Long,
  buffer: S3BlobStore.PartBuffer,
  multipart: Option[MultipartState],
  config: S3BlobStoreConfig,
  temp: zio.Ref[Option[TempResource]],
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
          for
            created <- ZIO.attemptBlocking {
                         val uploadKey = state.tempObjectKey
                         val req       =
                           CreateMultipartUploadRequest
                             .builder()
                             .bucket(state.config.tmp.bucket)
                             .key(uploadKey)
                             .build()
                         val resp      = client.createMultipartUpload(req)
                         MultipartState(
                           uploadId = resp.uploadId(),
                           key = uploadKey,
                           nextPartNumber = 1,
                           parts = Nil,
                         )
                       }
            _       <- state.temp.set(Some(TempResource(created.key, Some(created.uploadId))))
          yield state.copy(multipart = Some(created))

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
      metadata       <- ZIO
                          .fromEither(BlobMetadataV1.fromAttributes(validatedAttrs, S3BlobStore.ObjectChunker))
                          .mapError(msg => new IllegalStateException(s"Generated invalid blob metadata: $msg"))
      objectMetadata <- ZIO
                          .fromEither(S3BlobStore.encodeMetadata(metadata))
                          .mapError(msg => new IllegalStateException(s"Generated invalid S3 blob metadata: $msg"))
      _              <- multipart match
                          case None     =>
                            // Small object: upload directly to final key (buffer is bounded by partSize).
                            val req =
                              PutObjectRequest
                                .builder()
                                .bucket(config.blobs.bucket)
                                .key(finalObjectKeyFor(key))
                                .contentType(metadata.canonicalMediaType)
                                .metadata(objectMetadata)
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
                              _        <- temp.set(Some(TempResource(mp.key, None)))
                              _        <- S3BlobStore.promoteTempObject(
                                            client = client,
                                            sourceBucket = config.tmp.bucket,
                                            sourceKey = mp.key,
                                            destinationBucket = config.blobs.bucket,
                                            destinationKey = finalObjectKeyFor(key),
                                            size = totalBytes,
                                            contentType = metadata.canonicalMediaType,
                                            metadata = objectMetadata,
                                          )
                              deleted  <- ZIO
                                            .attemptBlocking(
                                              client.deleteObject(
                                                DeleteObjectRequest.builder().bucket(config.tmp.bucket).key(mp.key).build()
                                              )
                                            )
                                            .exit
                              _        <- ZIO.when(deleted.isSuccess)(temp.set(None))
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
    val base   = s"${S3BlobStore.algoPathSegment(key.bits.algo)}/${key.bits.digest.hex.value}-${key.bits.size}"
    val prefix = config.blobs.prefix.trim
    if prefix.isEmpty then base else s"${prefix.stripSuffix("/")}/$base"

object PutState:
  def initial(hasher: Hasher, config: S3BlobStoreConfig, temp: zio.Ref[Option[TempResource]]): PutState =
    PutState(hasher, totalBytes = 0L, buffer = S3BlobStore.PartBuffer.empty, multipart = None, config = config, temp = temp)

private final case class TempResource(key: String, uploadId: Option[String])

private final case class MultipartState(
  uploadId: String,
  key: String,
  nextPartNumber: Int,
  parts: List[CompletedPart],
)
