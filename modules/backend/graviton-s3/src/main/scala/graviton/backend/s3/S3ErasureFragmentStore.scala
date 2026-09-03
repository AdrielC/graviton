package graviton.backend.s3

import graviton.core.bytes.HashAlgo
import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlockStoredStatus, ErasureFragment, ErasureFragmentBytes}
import graviton.runtime.stores.{ErasureFragmentStore, StoreError, StoreOperation}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import zio.*

import java.security.MessageDigest
import java.util.Base64
import scala.jdk.CollectionConverters.*

/** One independently addressable S3 or Ceph RGW target for 2+1 erasure shards. */
final class S3ErasureFragmentStore(
  override val name: String,
  override val failureDomain: String,
  client: S3Client,
  config: S3Config,
) extends ErasureFragmentStore:
  import S3ErasureFragmentStore.*

  override def put(key: BinaryKey.Block, fragment: ErasureFragment): IO[StoreError, BlockStoredStatus] =
    write(key, fragment, replace = false).mapError(storeError(StoreOperation.PutBlock, Some(key)))

  override def repair(key: BinaryKey.Block, fragment: ErasureFragment): IO[StoreError, Unit] =
    write(key, fragment, replace = true).unit.mapError(storeError(StoreOperation.Repair, Some(key)))

  override def get(key: BinaryKey.Block, index: Int, expectedLength: Int): IO[StoreError, ErasureFragment] =
    (if expectedLength <= 0 || expectedLength > ErasureFragmentBytes.maxBytes then
       ZIO.fail(new IllegalArgumentException(s"Invalid expected erasure shard length: $expectedLength"))
     else
       val request = GetObjectRequest.builder().bucket(config.bucket).key(objectKeyFor(key, index)).build()
       ZIO.scoped {
         for
           response <-
             ZIO.acquireRelease(ZIO.attemptBlocking(client.getObject(request)))(stream => ZIO.attemptBlocking(stream.close()).orDie)
           bytes    <- ZIO.attemptBlocking(response.readNBytes(expectedLength + 1))
           _        <-
             ZIO
               .fail(new IllegalStateException(s"Shard $index for ${key.bits.render} has ${bytes.length} bytes, expected $expectedLength"))
               .unless(bytes.length == expectedLength)
           metadata  = response.response().metadata().asScala.toMap
           checksum <- sha256(bytes)
           _        <- verifyProof(key, index, expectedLength, checksum.hex, metadata)
           refined  <- ZIO.fromEither(ErasureFragmentBytes.fromChunk(Chunk.fromArray(bytes))).mapError(new IllegalStateException(_))
         yield ErasureFragment(index, refined)
       }
    ) .mapError(storeError(StoreOperation.GetBlock, Some(key)))

  override def healthCheck: IO[StoreError, Unit] =
    ZIO
      .attemptBlocking {
        client.headBucket(HeadBucketRequest.builder().bucket(config.bucket).build())
        ()
      }
      .mapError(storeError(StoreOperation.HealthCheck))

  private def storeError(operation: StoreOperation, key: Option[BinaryKey] = None)(error: Throwable): StoreError =
    S3StoreError.fromThrowable(operation, key)(error)

  private def write(key: BinaryKey.Block, fragment: ErasureFragment, replace: Boolean): Task[BlockStoredStatus] =
    val payload = fragment.chunk.toArray
    for
      checksum <- sha256(payload)
      metadata  = proof(key, fragment.index, payload.length, checksum.hex)
      builder   = PutObjectRequest
                    .builder()
                    .bucket(config.bucket)
                    .key(objectKeyFor(key, fragment.index))
                    .contentLength(payload.length.toLong)
                    .checksumSHA256(checksum.base64)
                    .metadata(metadata.asJava)
      request   = if replace then builder.build() else builder.ifNoneMatch("*").build()
      status   <- ZIO
                    .attemptBlocking(client.putObject(request, RequestBody.fromBytes(payload)))
                    .as(BlockStoredStatus.Fresh)
                    .catchSome {
                      case error: S3Exception if !replace && isPreconditionFailed(error) =>
                        verifyExisting(key, fragment.index, payload.length, checksum.hex).as(BlockStoredStatus.Duplicate)
                    }
      _        <- verifyExisting(key, fragment.index, payload.length, checksum.hex).when(replace)
    yield status

  private def verifyExisting(key: BinaryKey.Block, index: Int, length: Int, checksum: String): Task[Unit] =
    val request = HeadObjectRequest.builder().bucket(config.bucket).key(objectKeyFor(key, index)).build()
    ZIO.attemptBlocking(client.headObject(request)).flatMap { response =>
      verifyProof(key, index, length, checksum, response.metadata().asScala.toMap) *>
        ZIO
          .fail(new IllegalStateException(s"Shard $index length mismatch for ${key.bits.render}"))
          .unless(response.contentLength() == length.toLong)
          .unit
    }

  private def verifyProof(
    key: BinaryKey.Block,
    index: Int,
    length: Int,
    checksum: String,
    actual: Map[String, String],
  ): Task[Unit] =
    val expected = proof(key, index, length, checksum)
    ZIO
      .fail(new IllegalStateException(s"Shard $index has inconsistent erasure proof for ${key.bits.render}"))
      .unless(expected.forall { case (metadataKey, value) => actual.get(metadataKey).contains(value) })
      .unit

  private def proof(key: BinaryKey.Block, index: Int, length: Int, checksum: String): Map[String, String] =
    Map(
      SchemeMetadata      -> Scheme,
      ContentKeyMetadata  -> key.bits.render,
      ShardIndexMetadata  -> index.toString,
      ShardLengthMetadata -> length.toString,
      Sha256Metadata      -> checksum,
    )

  private def objectKeyFor(key: BinaryKey.Block, index: Int): String =
    val algo   = key.bits.algo match
      case HashAlgo.Sha256 => "sha256"
      case HashAlgo.Blake3 => "blake3"
      case other           => other.primaryName
    val base   = s".graviton-erasure/$Scheme/$algo/${key.bits.digest.hex.value}-${key.bits.size}/shard-$index"
    val prefix = config.prefix.trim.stripSuffix("/")
    if prefix.isEmpty then base else s"$prefix/$base"

  private def sha256(bytes: Array[Byte]): Task[DigestProof] =
    ZIO.attempt {
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      DigestProof(digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString, Base64.getEncoder.encodeToString(digest))
    }

  private def isPreconditionFailed(error: S3Exception): Boolean =
    error.statusCode() == 412 || Option(error.awsErrorDetails()).exists(_.errorCode() == "PreconditionFailed")

object S3ErasureFragmentStore:
  private final case class DigestProof(hex: String, base64: String)
  private val Scheme              = "xor-2-1-v1"
  private val SchemeMetadata      = "graviton-erasure-scheme"
  private val ContentKeyMetadata  = "graviton-content-key"
  private val ShardIndexMetadata  = "graviton-shard-index"
  private val ShardLengthMetadata = "graviton-shard-length"
  private val Sha256Metadata      = "graviton-shard-sha256"
