package graviton.pg.generated

import com.augustnagro.magnum.*
import com.augustnagro.magnum.pg.enums.*
import graviton.pg.given
import graviton.pg.PgRange
import io.github.iltotore.iron.*
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
case class Store(
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

object Store:
  type Id = (java.sql.Blob)

  case class Creator(
    key: java.sql.Blob,
    implId: UuidString,
    buildFp: java.sql.Blob,
    dvSchemaUrn: String :| MinLength[1] & MaxLength[2147483647],
    dvCanonicalBin: java.sql.Blob,
    dvJsonPreview: Option[String],
  ) derives DbCodec

  val StoreRepo = Repo[Store.Creator, Store, Store.Id]

@Table(PostgresDbType)
case class Blob(
  @Id
  @SqlName("id")
  id: String :| MinLength[1] & MaxLength[2147483647],
  @SqlName("algo_id")
  algoId: Short,
  @SqlName("hash")
  hash: java.sql.Blob,
  @SqlName("size_bytes")
  sizeBytes: Long,
  @SqlName("media_type_hint")
  mediaTypeHint: Option[String],
  @SqlName("created_at")
  createdAt: java.time.OffsetDateTime,
) derives DbCodec

object Blob:
  type Id = (String)

  case class Creator(
    algoId: Short,
    hash: java.sql.Blob,
    sizeBytes: Long,
    mediaTypeHint: Option[String],
  ) derives DbCodec

  val BlobRepo = Repo[Blob.Creator, Blob, Blob.Id]

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
case class ManifestEntry(
  @Id
  @SqlName("blob_id")
  blobId: String :| MinLength[1] & MaxLength[2147483647],
  @Id
  @SqlName("seq")
  seq: Int,
  @SqlName("block_algo_id")
  blockAlgoId: Short,
  @SqlName("block_hash")
  blockHash: java.sql.Blob,
  @SqlName("offset_bytes")
  offsetBytes: Long,
  @SqlName("size_bytes")
  sizeBytes: Long,
  @SqlName("span")
  span: Option[PgRange[Long]],
) derives DbCodec

object ManifestEntry:
  type Id = (String, Int)

  case class Creator(
    blobId: String :| MinLength[1] & MaxLength[2147483647],
    seq: Int,
    blockAlgoId: Short,
    blockHash: java.sql.Blob,
    offsetBytes: Long,
    sizeBytes: Long,
  ) derives DbCodec

  val ManifestEntryRepo = Repo[ManifestEntry.Creator, ManifestEntry, ManifestEntry.Id]

@Table(PostgresDbType)
case class Replica(
  @Id
  @SqlName("id")
  id: Long,
  @SqlName("algo_id")
  algoId: Short,
  @SqlName("hash")
  hash: java.sql.Blob,
  @SqlName("store_key")
  storeKey: java.sql.Blob,
  @SqlName("sector")
  sector: Option[String],
  @SqlName("status")
  status: String :| MinLength[1] & MaxLength[2147483647],
  @SqlName("size_bytes")
  sizeBytes: Long,
  @SqlName("etag")
  etag: Option[String],
  @SqlName("storage_class")
  storageClass: Option[String],
  @SqlName("first_seen_at")
  firstSeenAt: java.time.OffsetDateTime,
  @SqlName("last_verified_at")
  lastVerifiedAt: Option[java.time.OffsetDateTime],
) derives DbCodec

object Replica:
  type Id = (Long)

  case class Creator(
    algoId: Short,
    hash: java.sql.Blob,
    storeKey: java.sql.Blob,
    sector: Option[String],
    sizeBytes: Long,
    etag: Option[String],
    storageClass: Option[String],
    lastVerifiedAt: Option[java.time.OffsetDateTime],
  ) derives DbCodec

  val ReplicaRepo = Repo[Replica.Creator, Replica, Replica.Id]


// ZIO Schema definitions for public
object Schemas {
  given Schema[BuildInfo] = DeriveSchema.gen[BuildInfo]
  given Schema[BuildInfo.Creator] = DeriveSchema.gen[BuildInfo.Creator]
  given Schema[HashAlgorithm] = DeriveSchema.gen[HashAlgorithm]
  given Schema[HashAlgorithm.Creator] = DeriveSchema.gen[HashAlgorithm.Creator]
  given Schema[Store] = DeriveSchema.gen[Store]
  given Schema[Store.Creator] = DeriveSchema.gen[Store.Creator]
  given Schema[Blob] = DeriveSchema.gen[Blob]
  given Schema[Blob.Creator] = DeriveSchema.gen[Blob.Creator]
  given Schema[Block] = DeriveSchema.gen[Block]
  given Schema[Block.Creator] = DeriveSchema.gen[Block.Creator]
  given Schema[MerkleSnapshot] = DeriveSchema.gen[MerkleSnapshot]
  given Schema[MerkleSnapshot.Creator] = DeriveSchema.gen[MerkleSnapshot.Creator]
  given Schema[ManifestEntry] = DeriveSchema.gen[ManifestEntry]
  given Schema[ManifestEntry.Creator] = DeriveSchema.gen[ManifestEntry.Creator]
  given Schema[Replica] = DeriveSchema.gen[Replica]
  given Schema[Replica.Creator] = DeriveSchema.gen[Replica.Creator]
}
