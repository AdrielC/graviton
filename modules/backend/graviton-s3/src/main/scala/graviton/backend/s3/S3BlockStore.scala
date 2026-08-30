package graviton.backend.s3

import graviton.core.bytes.HashAlgo
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.model.*
import graviton.runtime.stores.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import zio.*
import zio.stream.*

import java.util.UUID
import java.util.Base64
import scala.jdk.CollectionConverters.*

final case class S3BlockStoreConfig(
  blocks: S3Config,
  scheme: String = "s3",
)

final class S3BlockStore(
  client: S3Client,
  config: S3BlockStoreConfig,
) extends RepairableBlockStore,
      BlockMaintenance,
      BlockTransferFootprint:

  override val transferBackend: StoreBackend = StoreBackend.S3

  override def blockWriteFootprint(maximumBlockBytes: Int): Either[TransferFootprint.Error, TransferFootprint] =
    TransferFootprint.single(TransferComponent.applyUnsafe("s3-request-body-copy"), maximumBlockBytes.toLong)

  override def putBlock(
    block: CanonicalBlock,
    plan: BlockWritePlan = BlockWritePlan(),
  ): IO[StoreError, StoredBlock] =
    storeBlock(block)
      .map(status => StoredBlock(block.key, block.size, status))
      .mapError(StoreError.fromThrowable(StoreOperation.PutBlock, StoreBackend.S3, retryUnknown = true))

  override def putBlocks(plan: BlockWritePlan = BlockWritePlan()): BlockSink =
    ZSink
      .foldLeftZIO(Acc.empty) { (acc, block: CanonicalBlock) =>
        for
          storedStatus <- storeBlock(block)
          entry        <- ZIO
                            .fromEither(BlockManifestEntry.make(acc.index, acc.offset, block.key, block.size.value))
                            .mapError(msg => new IllegalArgumentException(msg))
          next          = acc.next(entry, block, storedStatus)
        yield next
      }
      .mapZIO(_.toResult)
      .mapError(StoreError.fromThrowable(StoreOperation.PutBlock, StoreBackend.S3, retryUnknown = true))
      .ignoreLeftover

  override def get(key: BinaryKey.Block): ZStream[Any, StoreError, Byte] =
    val req =
      GetObjectRequest
        .builder()
        .bucket(config.blocks.bucket)
        .key(objectKeyFor(key))
        .build()

    ZStream
      .acquireReleaseWith(
        ZIO.attemptBlocking(client.getObject(req))
      )(is => ZIO.attemptBlocking(is.close()).orDie)
      .flatMap(is => ZStream.fromInputStream(is, chunkSize = 64 * 1024))
      .mapError {
        case error: S3Exception if isNotFound(error) => StoreError.NotFound(StoreOperation.GetBlock, key)
        case error                                   => StoreError.fromThrowable(StoreOperation.GetBlock, StoreBackend.S3, retryUnknown = true)(error)
      }

  override def exists(key: BinaryKey.Block): IO[StoreError, Boolean] =
    val req =
      HeadObjectRequest
        .builder()
        .bucket(config.blocks.bucket)
        .key(objectKeyFor(key))
        .build()

    ZIO
      .attemptBlocking(client.headObject(req))
      .as(true)
      .catchSome { case _: NoSuchKeyException => ZIO.succeed(false) }
      .catchSome {
        case error: S3Exception if error.statusCode() == 404 || Option(error.awsErrorDetails()).exists(_.errorCode() == "NoSuchKey") =>
          ZIO.succeed(false)
      }
      .mapError(StoreError.fromThrowable(StoreOperation.ExistsBlock, StoreBackend.S3, retryUnknown = true))

  override def repairBlock(block: CanonicalBlock): IO[StoreError, Unit] =
    (for
      _        <- ZIO
                    .fail(
                      new IllegalArgumentException(
                        s"Canonical block key declares ${block.key.bits.size} bytes but repair payload contains ${block.size.value}"
                      )
                    )
                    .unless(block.key.bits.size == block.size.value.toLong)
      payload   = block.bytes.toArray
      checksum <- sha256Checksum(block, payload)
      request   = PutObjectRequest
                    .builder()
                    .bucket(config.blocks.bucket)
                    .key(objectKeyFor(block.key))
                    .contentLength(payload.length.toLong)
                    .checksumSHA256(checksum)
                    .metadata(objectProof(block, checksum).asJava)
                    .build()
      _        <- ZIO.attemptBlocking(client.putObject(request, RequestBody.fromBytes(payload))).unit
      _        <- verifyExisting(block, checksum)
    yield ()).mapError(StoreError.fromThrowable(StoreOperation.Repair, StoreBackend.S3, retryUnknown = true))

  override def healthCheck: IO[StoreError, Unit] =
    ZIO
      .attemptBlocking {
        val request = HeadBucketRequest.builder().bucket(config.blocks.bucket).build()
        client.headBucket(request)
        ()
      }
      .mapError(StoreError.fromThrowable(StoreOperation.HealthCheck, StoreBackend.S3, retryUnknown = true))

  override def inventory: ZStream[Any, StoreError, BlockInventoryEntry] =
    ZStream
      .paginateChunkZIO("") { continuationToken =>
        ZIO.attemptBlocking {
          val builder  = ListObjectsV2Request
            .builder()
            .bucket(config.blocks.bucket)
            .prefix(activeListPrefix)
          if continuationToken.nonEmpty then
            val _ = builder.continuationToken(continuationToken)
          val response = client.listObjectsV2(builder.build())
          val entries  = Chunk.fromIterable(
            response
              .contents()
              .iterator()
              .asScala
              .filterNot(obj => obj.key().startsWith(quarantinePrefix))
              .map { obj =>
                val key = parseObjectKey(obj.key()).fold(
                  message => throw new IllegalStateException(s"Invalid block object '${obj.key()}': $message"),
                  identity,
                )
                BlockInventoryEntry(key, obj.size(), obj.lastModified())
              }
              .toList
          )
          (entries, Option(response.nextContinuationToken()).filter(_ => response.isTruncated))
        }
      }
      .mapError(StoreError.fromThrowable(StoreOperation.InventoryBlocks, StoreBackend.S3, retryUnknown = true))

  override def quarantine(entry: BlockInventoryEntry): IO[StoreError, QuarantinedBlock] =
    (for
      quarantinedAt <- Clock.instant
      token          = s"$quarantinePrefix${UUID.randomUUID()}/${objectKeyFor(entry.key).stripPrefix(activeListPrefix)}"
      _             <- copyObject(objectKeyFor(entry.key), token)
      _             <- deleteObject(objectKeyFor(entry.key))
    yield QuarantinedBlock(entry.key, token, entry.size, quarantinedAt))
      .mapError(StoreError.fromThrowable(StoreOperation.Quarantine, StoreBackend.S3, retryUnknown = true))

  override def restore(block: QuarantinedBlock): IO[StoreError, Unit] =
    (validateQuarantineToken(block.token) *>
      copyObject(block.token, objectKeyFor(block.key)) *>
      deleteObject(block.token)).mapError(StoreError.fromThrowable(StoreOperation.Restore, StoreBackend.S3, retryUnknown = true))

  override def purge(block: QuarantinedBlock): IO[StoreError, Unit] =
    (validateQuarantineToken(block.token) *> deleteObject(block.token))
      .mapError(StoreError.fromThrowable(StoreOperation.Purge, StoreBackend.S3, retryUnknown = true))

  private def storeBlock(block: CanonicalBlock): IO[Throwable, BlockStoredStatus] =
    for
      _        <- ZIO
                    .unless(block.key.bits.size == block.size.value.toLong)(
                      ZIO.fail(
                        new IllegalArgumentException(
                          s"Canonical block key declares ${block.key.bits.size} bytes but payload contains ${block.size.value}"
                        )
                      )
                    )
                    .unit
      payload   = block.bytes.toArray
      checksum <- sha256Checksum(block, payload)
      request   = PutObjectRequest
                    .builder()
                    .bucket(config.blocks.bucket)
                    .key(objectKeyFor(block.key))
                    .contentLength(payload.length.toLong)
                    .checksumSHA256(checksum)
                    .metadata(objectProof(block, checksum).asJava)
                    .ifNoneMatch("*")
                    .build()
      status   <- ZIO
                    .attemptBlocking(client.putObject(request, RequestBody.fromBytes(payload)))
                    .as(BlockStoredStatus.Fresh)
                    .catchSome {
                      case error: S3Exception if isPreconditionFailed(error) =>
                        verifyExisting(block, checksum)
                          .catchSome {
                            case missing: S3Exception if isNotFound(missing) =>
                              ZIO.fail(S3BlockStore.ConditionalWriteRace(missing))
                          }
                          .as(BlockStoredStatus.Duplicate)
                    }
                    .retry(
                      Schedule.recurWhile[Throwable](isConditionalConflict) &&
                        Schedule.spaced(25.millis) &&
                        Schedule.recurs(3)
                    )
    yield status

  private def verifyExisting(block: CanonicalBlock, checksum: String): Task[Unit] =
    val request =
      HeadObjectRequest
        .builder()
        .bucket(config.blocks.bucket)
        .key(objectKeyFor(block.key))
        .build()

    ZIO.attemptBlocking(client.headObject(request)).flatMap { response =>
      val expected = objectProof(block, checksum)
      val actual   = response.metadata().asScala.toMap
      if response.contentLength() != block.size.value.toLong then
        ZIO.fail(new IllegalStateException(s"Existing S3 block does not match content key ${block.key.bits.render}"))
      else if expected.forall { case (key, value) => actual.get(key).contains(value) } then ZIO.unit
      else ZIO.fail(new IllegalStateException(s"Existing S3 block has inconsistent CAS proof for ${block.key.bits.render}"))
    }

  private def objectProof(block: CanonicalBlock, checksum: String): Map[String, String] =
    Map(
      S3BlockStore.ProofVersionMetadata -> S3BlockStore.ProofVersion,
      S3BlockStore.ContentKeyMetadata   -> block.key.bits.render,
      S3BlockStore.Sha256Metadata       -> checksum,
    )

  private def sha256Checksum(block: CanonicalBlock, payload: Array[Byte]): Task[String] =
    block.key.bits.algo match
      case HashAlgo.Sha256 => ZIO.succeed(Base64.getEncoder.encodeToString(block.key.bits.digest.bytes))
      case _               =>
        ZIO.attempt {
          val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
          Base64.getEncoder.encodeToString(digest)
        }

  private def isPreconditionFailed(error: S3Exception): Boolean =
    error.statusCode() == 412 || Option(error.awsErrorDetails()).exists(_.errorCode() == "PreconditionFailed")

  private def isNotFound(error: S3Exception): Boolean =
    error.statusCode() == 404 || Option(error.awsErrorDetails()).exists(_.errorCode() == "NoSuchKey")

  private def isConditionalConflict(error: Throwable): Boolean =
    error match
      case _: S3BlockStore.ConditionalWriteRace => true
      case s3: S3Exception                      =>
        s3.statusCode() == 409 || Option(s3.awsErrorDetails()).exists(_.errorCode() == "ConditionalRequestConflict")
      case _                                    => false

  private def objectKeyFor(key: BinaryKey.Block): String =
    val algo = algoPathSegment(key.bits.algo)
    val hex  = key.bits.digest.hex.value
    val base = s"$algo/$hex-${key.bits.size}"

    val prefix = config.blocks.prefix.trim
    if prefix.isEmpty then base
    else s"${prefix.stripSuffix("/")}/$base"

  private val activeListPrefix: String =
    val prefix = config.blocks.prefix.trim.stripSuffix("/")
    if prefix.isEmpty then "" else s"$prefix/"

  private val quarantinePrefix: String =
    s"$activeListPrefix.graviton-quarantine/"

  private def parseObjectKey(objectKey: String): Either[String, BinaryKey.Block] =
    val relative = objectKey.stripPrefix(activeListPrefix)
    relative.split("/", 2).toList match
      case algoSegment :: fileName :: Nil =>
        fileName.lastIndexOf('-') match
          case separator if separator > 0 && separator < fileName.length - 1 =>
            val digest = fileName.substring(0, separator)
            val size   = fileName.substring(separator + 1)
            for
              bits <- KeyBits.fromString(s"$algoSegment:$digest:$size")
              key  <- BinaryKey.block(bits)
              _    <- Either.cond(objectKeyFor(key) == objectKey, (), "object key is not canonical")
            yield key
          case _                                                             => Left("expected <algorithm>/<hex>-<size>")
      case _                              => Left("expected <algorithm>/<hex>-<size>")

  private def copyObject(source: String, destination: String): Task[Unit] =
    ZIO.attemptBlocking {
      client.copyObject(
        CopyObjectRequest
          .builder()
          .sourceBucket(config.blocks.bucket)
          .sourceKey(source)
          .destinationBucket(config.blocks.bucket)
          .destinationKey(destination)
          .build()
      )
      ()
    }

  private def deleteObject(objectKey: String): Task[Unit] =
    ZIO.attemptBlocking {
      client.deleteObject(
        DeleteObjectRequest
          .builder()
          .bucket(config.blocks.bucket)
          .key(objectKey)
          .build()
      )
      ()
    }

  private def validateQuarantineToken(token: String): Task[Unit] =
    ZIO
      .unless(token.startsWith(quarantinePrefix))(
        ZIO.fail(new IllegalArgumentException("quarantine token is outside the configured prefix"))
      )
      .unit

  private def algoPathSegment(algo: HashAlgo): String =
    algo match
      case HashAlgo.Sha256 => "sha256"
      case HashAlgo.Blake3 => "blake3"
      case other           => other.primaryName

object S3BlockStore:

  private val ProofVersionMetadata = "graviton-cas-version"
  private val ContentKeyMetadata   = "graviton-content-key"
  private val Sha256Metadata       = "graviton-sha256"
  private val ProofVersion         = "1"

  private final case class ConditionalWriteRace(cause: Throwable)
      extends RuntimeException("Existing conditional-write target disappeared before verification", cause)

  /**
   * Explicit S3-compatible endpoint contract:
   *
   * Required:
   * - GRAVITON_S3_ENDPOINT
   * - GRAVITON_S3_ACCESS_KEY
   * - GRAVITON_S3_SECRET_KEY
   *
   * Optional:
   * - GRAVITON_S3_BLOCK_BUCKET (defaults to graviton-blocks)
   * - GRAVITON_S3_BLOCK_PREFIX (defaults to cas/blocks)
   * - GRAVITON_S3_REGION (defaults to us-east-1)
   */
  def fromEnvironment: Task[S3BlockStore] =
    for
      bucket <- ZIO.succeed(sys.env.get("GRAVITON_S3_BLOCK_BUCKET").filter(_.nonEmpty).getOrElse("graviton-blocks"))
      prefix <- ZIO.succeed(sys.env.get("GRAVITON_S3_BLOCK_PREFIX").filter(_.nonEmpty).getOrElse("cas/blocks"))
      base   <- ZIO
                  .fromEither(S3Config.fromEnvironment(bucket = bucket, prefix = prefix))
                  .mapError(msg => new IllegalArgumentException(msg))
      client <- S3ClientLayer.make(base)
    yield new S3BlockStore(client, S3BlockStoreConfig(blocks = base))

  val layerFromEnv: ZLayer[Any, Throwable, BlockStore] =
    ZLayer.fromZIO(fromEnvironment.map(store => store: BlockStore))

private final case class Acc(
  entries: ChunkBuilder[BlockManifestEntry],
  stored: ChunkBuilder[StoredBlock],
  offset: Long,
  index: Long,
):
  def next(entry: BlockManifestEntry, block: CanonicalBlock, status: BlockStoredStatus): Acc =
    entries += entry
    stored += StoredBlock(block.key, block.size, status)
    copy(
      offset = offset + block.size.value.toLong,
      index = index + 1L,
    )

  def toResult: IO[Throwable, BlockBatchResult] =
    ZIO
      .fromEither(BlockManifest.build(entries.result()))
      .mapError(msg => new IllegalArgumentException(msg))
      .map { manifest =>
        BlockBatchResult(
          manifest = manifest,
          stored = stored.result(),
          forward = Chunk.empty,
          frames = Chunk.empty,
        )
      }

private object Acc:
  def empty: Acc =
    Acc(
      entries = ChunkBuilder.make[BlockManifestEntry](),
      stored = ChunkBuilder.make[StoredBlock](),
      offset = 0L,
      index = 0L,
    )
