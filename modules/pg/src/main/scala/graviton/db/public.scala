package graviton.pg.generated

import com.augustnagro.magnum.*
import graviton.pg.given
import graviton.pg.PgRange

@Table(PostgresDbType)
case class BlobStore(
  @Id
  @SqlName("key")
  key: java.sql.Blob,
  @SqlName("impl_id")
  implId: String,
  @SqlName("build_fp")
  buildFp: java.sql.Blob,
  @SqlName("dv_schema_urn")
  dvSchemaUrn: String,
  @SqlName("dv_canonical_bin")
  dvCanonicalBin: java.sql.Blob,
  @SqlName("dv_json_preview")
  dvJsonPreview: Option[String],
  @SqlName("status")
  status: String,
  @SqlName("version")
  version: Long,
  @SqlName("created_at")
  createdAt: java.time.OffsetDateTime,
  @SqlName("updated_at")
  updatedAt: java.time.OffsetDateTime,
  @SqlName("dv_hash")
  dvHash: Option[java.sql.Blob],
) derives DbCodec
object BlobStore:
  type Id = (java.sql.Blob)

  case class Creator(
    key: java.sql.Blob,
    implId: String,
    buildFp: java.sql.Blob,
    dvSchemaUrn: String,
    dvCanonicalBin: java.sql.Blob,
    dvJsonPreview: Option[String],
  ) derives DbCodec
  val BlobStoreRepo = Repo[BlobStore.Creator, BlobStore, BlobStore.Id]

@Table(PostgresDbType)
case class BuildInfo(
  @Id
  @SqlName("id")
  id: Long,
  @SqlName("app_name")
  appName: String,
  @SqlName("version")
  version: String,
  @SqlName("git_sha")
  gitSha: String,
  @SqlName("scala_version")
  scalaVersion: String,
  @SqlName("zio_version")
  zioVersion: String,
  @SqlName("built_at")
  builtAt: java.time.OffsetDateTime,
  @SqlName("launched_at")
  launchedAt: java.time.OffsetDateTime,
  @SqlName("is_current")
  isCurrent: Boolean,
) derives DbCodec
object BuildInfo:
  type Id = (Long)

  case class Creator(
    appName: String,
    version: String,
    gitSha: String,
    scalaVersion: String,
    zioVersion: String,
    builtAt: java.time.OffsetDateTime,
    launchedAt: java.time.OffsetDateTime,
  ) derives DbCodec
  val BuildInfoRepo = Repo[BuildInfo.Creator, BuildInfo, BuildInfo.Id]

@Table(PostgresDbType)
case class HashAlgorithm(
  @Id
  @SqlName("id")
  id: Short,
  @SqlName("name")
  name: String,
  @SqlName("is_fips")
  isFips: Boolean,
) derives DbCodec
object HashAlgorithm:
  type Id = (Short)

  case class Creator(
    name: String
  ) derives DbCodec
  val HashAlgorithmRepo = Repo[HashAlgorithm.Creator, HashAlgorithm, HashAlgorithm.Id]

@Table(PostgresDbType)
case class Block(
  @Id
  @SqlName("algo_id")
  algoId: Short,
  @Id
  @SqlName("hash")
  hash: java.sql.Blob,
  @SqlName("size_bytes")
  sizeBytes: Long,
  @SqlName("created_at")
  createdAt: java.time.OffsetDateTime,
  @SqlName("inline_bytes")
  inlineBytes: Option[java.sql.Blob],
) derives DbCodec
object Block:
  type Id = (Short, java.sql.Blob)

  case class Creator(
    algoId: Short,
    hash: java.sql.Blob,
    sizeBytes: Long,
    inlineBytes: Option[java.sql.Blob],
  ) derives DbCodec
  val BlockRepo = Repo[Block.Creator, Block, Block.Id]

@Table(PostgresDbType)
case class File(
  @Id
  @SqlName("id")
  id: String,
  @SqlName("algo_id")
  algoId: Short,
  @SqlName("hash")
  hash: java.sql.Blob,
  @SqlName("size_bytes")
  sizeBytes: Long,
  @SqlName("media_type")
  mediaType: Option[String],
  @SqlName("created_at")
  createdAt: java.time.OffsetDateTime,
) derives DbCodec
object File:
  type Id = (String)

  case class Creator(
    algoId: Short,
    hash: java.sql.Blob,
    sizeBytes: Long,
    mediaType: Option[String],
  ) derives DbCodec
  val FileRepo = Repo[File.Creator, File, File.Id]

@Table(PostgresDbType)
case class MerkleSnapshot(
  @Id
  @SqlName("id")
  id: Long,
  @SqlName("query_fingerprint")
  queryFingerprint: java.sql.Blob,
  @SqlName("algo_id")
  algoId: Short,
  @SqlName("root_hash")
  rootHash: java.sql.Blob,
  @SqlName("at_time")
  atTime: java.time.OffsetDateTime,
  @SqlName("note")
  note: Option[String],
) derives DbCodec
object MerkleSnapshot:
  type Id = (Long)

  case class Creator(
    queryFingerprint: java.sql.Blob,
    algoId: Short,
    rootHash: java.sql.Blob,
    note: Option[String],
  ) derives DbCodec
  val MerkleSnapshotRepo = Repo[MerkleSnapshot.Creator, MerkleSnapshot, MerkleSnapshot.Id]

@Table(PostgresDbType)
case class BlockLocation(
  @Id
  @SqlName("id")
  id: Long,
  @SqlName("algo_id")
  algoId: Short,
  @SqlName("hash")
  hash: java.sql.Blob,
  @SqlName("blob_store_key")
  blobStoreKey: java.sql.Blob,
  @SqlName("uri")
  uri: Option[String],
  @SqlName("status")
  status: String,
  @SqlName("bytes_length")
  bytesLength: Long,
  @SqlName("etag")
  etag: Option[String],
  @SqlName("storage_class")
  storageClass: Option[String],
  @SqlName("first_seen_at")
  firstSeenAt: java.time.OffsetDateTime,
  @SqlName("last_verified_at")
  lastVerifiedAt: Option[java.time.OffsetDateTime],
) derives DbCodec
object BlockLocation:
  type Id = (Long)

  case class Creator(
    algoId: Short,
    hash: java.sql.Blob,
    blobStoreKey: java.sql.Blob,
    uri: Option[String],
    bytesLength: Long,
    etag: Option[String],
    storageClass: Option[String],
    lastVerifiedAt: Option[java.time.OffsetDateTime],
  ) derives DbCodec
  val BlockLocationRepo = Repo[BlockLocation.Creator, BlockLocation, BlockLocation.Id]

@Table(PostgresDbType)
case class FileBlock(
  @Id
  @SqlName("file_id")
  fileId: String,
  @Id
  @SqlName("seq")
  seq: Int,
  @SqlName("block_algo_id")
  blockAlgoId: Short,
  @SqlName("block_hash")
  blockHash: java.sql.Blob,
  @SqlName("offset_bytes")
  offsetBytes: Long,
  @SqlName("length_bytes")
  lengthBytes: Long,
  @SqlName("span")
  span: Option[PgRange[Long]],
) derives DbCodec
object FileBlock:
  type Id = (String, Int)

  case class Creator(
    fileId: String,
    seq: Int,
    blockAlgoId: Short,
    blockHash: java.sql.Blob,
    offsetBytes: Long,
    lengthBytes: Long,
  ) derives DbCodec
  val FileBlockRepo = Repo[FileBlock.Creator, FileBlock, FileBlock.Id]

@Table(PostgresDbType)
case class Blob(
  @SqlName("blob_id")
  blobId: Option[String],
  @SqlName("algo_id")
  algoId: Option[Short],
  @SqlName("hash")
  hash: Option[java.sql.Blob],
  @SqlName("total_size")
  totalSize: Option[Long],
  @SqlName("media_type_hint")
  mediaTypeHint: Option[String],
  @SqlName("created_at")
  createdAt: Option[java.time.OffsetDateTime],
) derives DbCodec
object Blob:
  type Id = Null

  val BlobRepo = ImmutableRepo[Blob, Blob.Id]

@Table(PostgresDbType)
case class ManifestEntry(
  @SqlName("blob_id")
  blobId: Option[String],
  @SqlName("seq")
  seq: Option[Int],
  @SqlName("offset")
  offset: Option[Long],
  @SqlName("size")
  size: Option[Long],
  @SqlName("algo_id")
  algoId: Option[Short],
  @SqlName("hash")
  hash: Option[java.sql.Blob],
) derives DbCodec
object ManifestEntry:
  type Id = Null

  val ManifestEntryRepo = ImmutableRepo[ManifestEntry, ManifestEntry.Id]

@Table(PostgresDbType)
case class Replica(
  @SqlName("algo_id")
  algoId: Option[Short],
  @SqlName("hash")
  hash: Option[java.sql.Blob],
  @SqlName("store_key")
  storeKey: Option[java.sql.Blob],
  @SqlName("sector")
  sector: Option[String],
  @SqlName("status")
  status: Option[String],
  @SqlName("size_bytes")
  sizeBytes: Option[Long],
  @SqlName("etag")
  etag: Option[String],
  @SqlName("storage_class")
  storageClass: Option[String],
  @SqlName("first_seen_at")
  firstSeenAt: Option[java.time.OffsetDateTime],
  @SqlName("last_verified_at")
  lastVerifiedAt: Option[java.time.OffsetDateTime],
) derives DbCodec
object Replica:
  type Id = Null

  val ReplicaRepo = ImmutableRepo[Replica, Replica.Id]
