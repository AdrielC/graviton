package graviton.pg.generated

import com.augustnagro.magnum.*
import graviton.db.{*, given}
import zio.Chunk
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}

@Table(PostgresDbType)
final case class HashAlgorithm(
  @Id
  @SqlName("id")
  id: Short,
  @SqlName("name")
  name: String,
  @SqlName("is_fips")
  isFips: Boolean,
) derives DbCodec

object HashAlgorithm:
  type Id = Short

  final case class Creator(
    name: String
  ) derives DbCodec

  val repo = Repo[HashAlgorithm.Creator, HashAlgorithm, HashAlgorithm.Id]

@Table(PostgresDbType)
final case class BuildInfo(
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
  type Id = Long

  final case class Creator(
    appName: String,
    version: String,
    gitSha: String,
    scalaVersion: String,
    zioVersion: String,
    builtAt: java.time.OffsetDateTime,
    launchedAt: java.time.OffsetDateTime,
  ) derives DbCodec

  val repo = Repo[BuildInfo.Creator, BuildInfo, BuildInfo.Id]

@Table(PostgresDbType)
final case class Store(
  @Id
  @SqlName("key")
  key: StoreKey,
  @SqlName("impl_id")
  implId: String,
  @SqlName("build_fp")
  buildFp: Chunk[Byte],
  @SqlName("dv_schema_urn")
  dvSchemaUrn: String,
  @SqlName("dv_canonical_bin")
  dvCanonicalBin: Chunk[Byte],
  @SqlName("dv_json_preview")
  dvJsonPreview: Option[Json],
  @SqlName("status")
  status: StoreStatus,
  @SqlName("version")
  version: NonNegLong,
  @SqlName("created_at")
  createdAt: java.time.OffsetDateTime,
  @SqlName("updated_at")
  updatedAt: java.time.OffsetDateTime,
  @SqlName("dv_hash")
  dvHash: Chunk[Byte],
) derives DbCodec

object Store:
  type Id = StoreKey

  final case class Creator(
    key: StoreKey,
    implId: String,
    buildFp: Chunk[Byte],
    dvSchemaUrn: String,
    dvCanonicalBin: Chunk[Byte],
    dvJsonPreview: Option[Json],
  ) derives DbCodec

  val repo = Repo[Store.Creator, Store, Store.Id]

@Table(PostgresDbType)
final case class Blob(
  @Id
  @SqlName("id")
  id: java.util.UUID,
  @SqlName("algo_id")
  algoId: Short,
  @SqlName("hash")
  hash: HashBytes,
  @SqlName("size_bytes")
  sizeBytes: PosLong,
  @SqlName("media_type_hint")
  mediaTypeHint: Option[String],
  @SqlName("created_at")
  createdAt: java.time.OffsetDateTime,
) derives DbCodec

object Blob:
  type Id = java.util.UUID

  final case class Creator(
    algoId: Short,
    hash: HashBytes,
    sizeBytes: PosLong,
    mediaTypeHint: Option[String],
  ) derives DbCodec

  val repo = Repo[Blob.Creator, Blob, Blob.Id]

@Table(PostgresDbType)
final case class Block(
  @Id
  @SqlName("algo_id")
  algoId: Short,
  @Id
  @SqlName("hash")
  hash: HashBytes,
  @SqlName("size_bytes")
  sizeBytes: PosLong,
  @SqlName("created_at")
  createdAt: java.time.OffsetDateTime,
  @SqlName("inline_bytes")
  inlineBytes: Option[SmallBytes],
) derives DbCodec

object Block:
  type Id = (Short, HashBytes)

  final case class Creator(
    algoId: Short,
    hash: HashBytes,
    sizeBytes: PosLong,
    inlineBytes: Option[SmallBytes],
  ) derives DbCodec

  val repo = Repo[Block.Creator, Block, Block.Id]

@Table(PostgresDbType)
final case class ManifestEntry(
  @Id
  @SqlName("blob_id")
  blobId: java.util.UUID,
  @Id
  @SqlName("seq")
  seq: Int,
  @SqlName("block_algo_id")
  blockAlgoId: Short,
  @SqlName("block_hash")
  blockHash: HashBytes,
  @SqlName("offset_bytes")
  offsetBytes: NonNegLong,
  @SqlName("size_bytes")
  sizeBytes: PosLong,
  @SqlName("span")
  span: DbRange[Long],
) derives DbCodec

object ManifestEntry:
  type Id = (java.util.UUID, Int)

  final case class Creator(
    blobId: java.util.UUID,
    seq: Int,
    blockAlgoId: Short,
    blockHash: HashBytes,
    offsetBytes: NonNegLong,
    sizeBytes: PosLong,
  ) derives DbCodec

  val repo = Repo[ManifestEntry.Creator, ManifestEntry, ManifestEntry.Id]

@Table(PostgresDbType)
final case class Replica(
  @Id
  @SqlName("id")
  id: Long,
  @SqlName("algo_id")
  algoId: Short,
  @SqlName("hash")
  hash: HashBytes,
  @SqlName("store_key")
  storeKey: StoreKey,
  @SqlName("sector")
  sector: Option[String],
  @SqlName("status")
  status: ReplicaStatus,
  @SqlName("size_bytes")
  sizeBytes: PosLong,
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
  type Id = Long

  final case class Creator(
    algoId: Short,
    hash: HashBytes,
    storeKey: StoreKey,
    sector: Option[String],
    sizeBytes: PosLong,
    etag: Option[String],
    storageClass: Option[String],
    lastVerifiedAt: Option[java.time.OffsetDateTime],
  ) derives DbCodec

  val repo = Repo[Replica.Creator, Replica, Replica.Id]

@Table(PostgresDbType)
final case class MerkleSnapshot(
  @Id
  @SqlName("id")
  id: Long,
  @SqlName("query_fingerprint")
  queryFingerprint: Chunk[Byte],
  @SqlName("algo_id")
  algoId: Short,
  @SqlName("root_hash")
  rootHash: HashBytes,
  @SqlName("at_time")
  atTime: java.time.OffsetDateTime,
  @SqlName("note")
  note: Option[String],
) derives DbCodec

object MerkleSnapshot:
  type Id = Long

  final case class Creator(
    queryFingerprint: Chunk[Byte],
    algoId: Short,
    rootHash: HashBytes,
    note: Option[String],
  ) derives DbCodec

  val repo = Repo[MerkleSnapshot.Creator, MerkleSnapshot, MerkleSnapshot.Id]

object Schemas {
  given hashAlgorithmSchema: Schema[HashAlgorithm]                  = DeriveSchema.gen[HashAlgorithm]
  given hashAlgorithmCreatorSchema: Schema[HashAlgorithm.Creator]   = DeriveSchema.gen[HashAlgorithm.Creator]
  given buildInfoSchema: Schema[BuildInfo]                          = DeriveSchema.gen[BuildInfo]
  given buildInfoCreatorSchema: Schema[BuildInfo.Creator]           = DeriveSchema.gen[BuildInfo.Creator]
  given storeSchema: Schema[Store]                                  = DeriveSchema.gen[Store]
  given storeCreatorSchema: Schema[Store.Creator]                   = DeriveSchema.gen[Store.Creator]
  given blobSchema: Schema[Blob]                                    = DeriveSchema.gen[Blob]
  given blobCreatorSchema: Schema[Blob.Creator]                     = DeriveSchema.gen[Blob.Creator]
  given blockSchema: Schema[Block]                                  = DeriveSchema.gen[Block]
  given blockCreatorSchema: Schema[Block.Creator]                   = DeriveSchema.gen[Block.Creator]
  given manifestEntrySchema: Schema[ManifestEntry]                  = DeriveSchema.gen[ManifestEntry]
  given manifestEntryCreatorSchema: Schema[ManifestEntry.Creator]   = DeriveSchema.gen[ManifestEntry.Creator]
  given replicaSchema: Schema[Replica]                              = DeriveSchema.gen[Replica]
  given replicaCreatorSchema: Schema[Replica.Creator]               = DeriveSchema.gen[Replica.Creator]
  given merkleSnapshotSchema: Schema[MerkleSnapshot]                = DeriveSchema.gen[MerkleSnapshot]
  given merkleSnapshotCreatorSchema: Schema[MerkleSnapshot.Creator] = DeriveSchema.gen[MerkleSnapshot.Creator]
}
