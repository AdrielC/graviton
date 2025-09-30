package graviton.pg.generated

import com.augustnagro.magnum.*
import com.augustnagro.magnum.pg.enums.*
import graviton.pg.given
import graviton.pg.PgRange
import io.github.iltotore.iron.{zio => _, *}
import io.github.iltotore.iron.constraint.all.*
import zio.schema.*
import zio.schema.annotation.*

// Iron Type Aliases for Database Constraints
type NonEmptyString = String :| MinLength[1]
type EmailString = String :| Match["^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"]
type PositiveInt = Int :| Positive
type NonNegativeInt = Int :| GreaterEqual[0]
type UuidString = String :| Match["^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"]

@Table(PostgresDbType)
case class BlobStore(
  @Id
  @SqlName("key")
  key: java.sql.Blob,
  @SqlName("impl_id")
  implId: UuidString,
  @SqlName("build_fp")
  buildFp: java.sql.Blob,
  @SqlName("dv_schema_urn")
  dvSchemaUrn: String :| MinLength[1] & MaxLength[2147483647],
  @SqlName("dv_canonical_bin")
  dvCanonicalBin: java.sql.Blob,
  @SqlName("dv_json_preview")
  dvJsonPreview: Option[String],
  @SqlName("status")
  status: String :| MinLength[1] & MaxLength[2147483647],
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
    implId: UuidString,
    buildFp: java.sql.Blob,
    dvSchemaUrn: String :| MinLength[1] & MaxLength[2147483647],
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
  appName: String :| MinLength[1] & MaxLength[2147483647],
  @SqlName("version")
  version: String :| MinLength[1] & MaxLength[2147483647],
  @SqlName("git_sha")
  gitSha: String :| MinLength[1] & MaxLength[2147483647],
  @SqlName("scala_version")
  scalaVersion: String :| MinLength[1] & MaxLength[2147483647],
  @SqlName("zio_version")
  zioVersion: String :| MinLength[1] & MaxLength[2147483647],
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
    appName: String :| MinLength[1] & MaxLength[2147483647],
    version: String :| MinLength[1] & MaxLength[2147483647],
    gitSha: String :| MinLength[1] & MaxLength[2147483647],
    scalaVersion: String :| MinLength[1] & MaxLength[2147483647],
    zioVersion: String :| MinLength[1] & MaxLength[2147483647],
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
  name: String :| MinLength[1] & MaxLength[2147483647],
  @SqlName("is_fips")
  isFips: Boolean,
) derives DbCodec

object HashAlgorithm:
  type Id = (Short)

  case class Creator(
    name: String :| MinLength[1] & MaxLength[2147483647],
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
  id: String :| MinLength[1] & MaxLength[2147483647],
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
  status: String :| MinLength[1] & MaxLength[2147483647],
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
  fileId: String :| MinLength[1] & MaxLength[2147483647],
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
    fileId: String :| MinLength[1] & MaxLength[2147483647],
    seq: Int,
    blockAlgoId: Short,
    blockHash: java.sql.Blob,
    offsetBytes: Long,
    lengthBytes: Long,
  ) derives DbCodec

  val FileBlockRepo = Repo[FileBlock.Creator, FileBlock, FileBlock.Id]


// ZIO Schema definitions for public
object Schemas {
  given Schema[BlobStore] = DeriveSchema.gen[BlobStore]
  given Schema[BlobStore.Creator] = DeriveSchema.gen[BlobStore.Creator]
  given Schema[BuildInfo] = DeriveSchema.gen[BuildInfo]
  given Schema[BuildInfo.Creator] = DeriveSchema.gen[BuildInfo.Creator]
  given Schema[HashAlgorithm] = DeriveSchema.gen[HashAlgorithm]
  given Schema[HashAlgorithm.Creator] = DeriveSchema.gen[HashAlgorithm.Creator]
  given Schema[Block] = DeriveSchema.gen[Block]
  given Schema[Block.Creator] = DeriveSchema.gen[Block.Creator]
  given Schema[File] = DeriveSchema.gen[File]
  given Schema[File.Creator] = DeriveSchema.gen[File.Creator]
  given Schema[MerkleSnapshot] = DeriveSchema.gen[MerkleSnapshot]
  given Schema[MerkleSnapshot.Creator] = DeriveSchema.gen[MerkleSnapshot.Creator]
  given Schema[BlockLocation] = DeriveSchema.gen[BlockLocation]
  given Schema[BlockLocation.Creator] = DeriveSchema.gen[BlockLocation.Creator]
  given Schema[FileBlock] = DeriveSchema.gen[FileBlock]
  given Schema[FileBlock.Creator] = DeriveSchema.gen[FileBlock.Creator]
}
