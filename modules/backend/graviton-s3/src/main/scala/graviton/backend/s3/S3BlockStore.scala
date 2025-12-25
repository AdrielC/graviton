package graviton.backend.s3

import graviton.core.bytes.HashAlgo
import graviton.core.keys.BinaryKey
import graviton.runtime.model.*
import graviton.runtime.stores.BlockStore
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import zio.*
import zio.stream.*

final case class S3BlockStoreConfig(
  blocks: S3Config,
  scheme: String = "s3",
)

final class S3BlockStore(
  client: S3Client,
  config: S3BlockStoreConfig,
) extends BlockStore:

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
      .ignoreLeftover

  override def get(key: BinaryKey.Block): ZStream[Any, Throwable, Byte] =
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

  override def exists(key: BinaryKey.Block): ZIO[Any, Throwable, Boolean] =
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
      .catchSome { case _: S3Exception =>
        // MinIO may raise generic S3Exception for missing keys.
        ZIO.succeed(false)
      }

  private def storeBlock(block: CanonicalBlock): IO[Throwable, BlockStoredStatus] =
    exists(block.key).flatMap { present =>
      if present then ZIO.succeed(BlockStoredStatus.Duplicate)
      else
        val req =
          PutObjectRequest
            .builder()
            .bucket(config.blocks.bucket)
            .key(objectKeyFor(block.key))
            .contentLength(block.bytes.length.toLong)
            .build()

        ZIO
          .attemptBlocking(client.putObject(req, RequestBody.fromBytes(block.bytes.toArray)))
          .as(BlockStoredStatus.Fresh)
    }

  private def objectKeyFor(key: BinaryKey.Block): String =
    val algo = algoPathSegment(key.bits.algo)
    val hex  = key.bits.digest.hex.value
    val base = s"$algo/$hex-${key.bits.size}"

    val prefix = config.blocks.prefix.trim
    if prefix.isEmpty then base
    else s"${prefix.stripSuffix("/")}/$base"

  private def algoPathSegment(algo: HashAlgo): String =
    algo match
      case HashAlgo.Sha256 => "sha256"
      case HashAlgo.Blake3 => "blake3"
      case other           => other.primaryName

object S3BlockStore:

  /**
   * Minimal env contract for the on-prem v1 compose bundle:
   * - QUASAR_MINIO_URL, MINIO_ROOT_USER, MINIO_ROOT_PASSWORD
   * - GRAVITON_S3_BLOCK_BUCKET (defaults to graviton-blocks)
   * - GRAVITON_S3_BLOCK_PREFIX (defaults to cas/blocks)
   */
  val layerFromEnv: ZLayer[Any, Throwable, BlockStore] =
    ZLayer.fromZIO {
      val bucket = sys.env.get("GRAVITON_S3_BLOCK_BUCKET").filter(_.nonEmpty).getOrElse("graviton-blocks")
      val prefix = sys.env.get("GRAVITON_S3_BLOCK_PREFIX").filter(_.nonEmpty).getOrElse("cas/blocks")

      for
        base   <- ZIO
                    .fromEither(S3Config.fromMinioEnv(bucketEnv = "GRAVITON_S3_BLOCK_BUCKET"))
                    .map(_.copy(bucket = bucket, prefix = prefix))
                    .mapError(msg => new IllegalArgumentException(msg))
        client <- S3ClientLayer.make(base)
      yield new S3BlockStore(client, S3BlockStoreConfig(blocks = base))
    }

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
  val empty: Acc =
    Acc(
      entries = ChunkBuilder.make[BlockManifestEntry](),
      stored = ChunkBuilder.make[StoredBlock](),
      offset = 0L,
      index = 0L,
    )
